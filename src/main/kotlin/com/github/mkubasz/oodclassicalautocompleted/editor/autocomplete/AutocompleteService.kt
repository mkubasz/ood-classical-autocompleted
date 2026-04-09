package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteCapability
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProvider
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProviderFactory
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.CompletionResponse
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEventListener
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.command.WriteCommandAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class AutocompleteService(private val project: Project) : Disposable {

    private val log = logger<AutocompleteService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rejectionCache = RejectionCache()
    private val inlineStates = ConcurrentHashMap<Editor, InlineState>()
    private val nextEditStates = ConcurrentHashMap<Editor, NextEditState>()
    private val supportedEditors = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    private val caretSuppression = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
    private val pendingSuggestions = ConcurrentHashMap<Editor, InlineCompletionCandidate>()
    private val requestSequence = AtomicLong(0)

    private var inlineCache = createInlineCache()
    private var currentSettingsHash: Int? = null
    private var fimProvider: AutocompleteProvider? = null
    private var nextEditProvider: AutocompleteProvider? = null
    private var terminalProvider: AutocompleteProvider? = null
    private var currentProviderType: AutocompleteProviderType? = null

    val hasActiveSuggestion: Boolean
        get() = inlineStates.values.any { it.visible } || nextEditStates.isNotEmpty()

    fun installInlineSupport(editor: Editor) {
        if (editor.project != project || editor.isViewer) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater(
                { installInlineSupport(editor) },
                project.disposed
            )
            return
        }
        val editorEx = editor as? EditorEx ?: return
        synchronized(supportedEditors) {
            if (!supportedEditors.add(editor)) return
        }

        InlineCompletion.install(editorEx, scope)
        InlineCompletion.getHandlerOrNull(editor)?.addEventListener(
            object : InlineCompletionEventListener {
                override fun on(event: InlineCompletionEventType) {
                    when (event) {
                        is InlineCompletionEventType.Show -> onInlineShown(editor)
                        is InlineCompletionEventType.Hide -> clearInlineState(editor)
                        InlineCompletionEventType.Insert -> clearInlineState(editor)
                        else -> Unit
                    }
                }
            }
        )
    }

    fun unregisterEditor(editor: Editor) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater(
                { unregisterEditor(editor) },
                project.disposed
            )
            return
        }
        synchronized(supportedEditors) {
            supportedEditors.remove(editor)
        }
        pendingSuggestions.remove(editor)
        clearInlineState(editor)
        clearNextEdit(editor)
        InlineCompletion.remove(editor)
    }

    suspend fun fetchInlineCandidate(request: InlineCompletionRequest): InlineCompletionCandidate? {
        val settings = PluginSettings.getInstance().state
        if (!settings.autocompleteEnabled || !PluginSettings.getInstance().isConfigured) return null

        val tStart = System.nanoTime()
        val requestStartedAt = System.currentTimeMillis()
        delay(settings.debounceMs)

        val snapshot = ApplicationManager.getApplication().runReadAction<CompletionContextSnapshot?> {
            buildSnapshot(request)
        } ?: return null
        val tAfterContext = System.nanoTime()

        val triggerDecision = AutocompleteTriggerHeuristics.evaluate(
            documentText = snapshot.documentText,
            offset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            maxDocumentChars = MAX_DOCUMENT_CHARS,
            lookbackForNonWhitespace = LOOKBACK_FOR_NON_WHITESPACE,
        )
        if (!triggerDecision.shouldRequest) {
            metric("inline.skip", "reason" to triggerDecision.reason)
            return null
        }

        ensureProviders()
        val stateHash = PluginSettings.getInstance().state.hashCode()
        val autocompleteRequest = buildAutocompleteRequest(snapshot)
        val editor = request.editor
        val requestId = requestSequence.incrementAndGet()

        launchNextEditFetch(
            editor = editor,
            autocompleteRequest = autocompleteRequest,
            snapshot = snapshot,
            requestId = requestId,
        )

        val inlineProvider = selectInlineProvider(snapshot)
        val inlineFallbackProvider = if (snapshot.isTerminal) null
            else nextEditProvider?.takeIf { AutocompleteCapability.INLINE in it.capabilities }
        if (inlineProvider == null && inlineFallbackProvider == null) return null
        val stablePrefix = snapshot.prefixWindow.dropLast(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.prefixWindow.length)
        )
        val stableSuffix = snapshot.suffixWindow.drop(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.suffixWindow.length)
        )
        val providerKey = if (snapshot.isTerminal) "terminal" else PluginSettings.getInstance().state.autocompleteProvider.name
        val cacheKey = InlineSuggestionCache.Key(
            providerKey = providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixStableHash = stablePrefix.hashCode(),
            suffixStableHash = stableSuffix.hashCode(),
        )

        val prefixTail = snapshot.prefixWindow.takeLast(PROXIMITY_PREFIX_TAIL_CHARS)

        inlineCache.get(cacheKey)?.let { cached ->
            val filtered = filterInlineCandidates(
                snapshot,
                prepareInlineCandidates(snapshot, cached, autocompleteRequest),
            )
            if (filtered.isNotEmpty()) {
                storeInlineState(editor, requestId, filtered)
                metric("inline.cache.hit", "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "cache")
                delayForDwell(requestStartedAt)
                return filtered.first()
            }
        }

        inlineCache.getProximity(
            providerKey = PluginSettings.getInstance().state.autocompleteProvider.name,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixTail = prefixTail,
        )?.let { proxCandidates ->
            val filtered = filterInlineCandidates(snapshot, proxCandidates)
            if (filtered.isNotEmpty()) {
                storeInlineState(editor, requestId, filtered)
                metric("inline.cache.proximity_hit", "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "proximity")
                delayForDwell(requestStartedAt)
                return filtered.first()
            }
        }
        metric("inline.cache.miss", "offset" to snapshot.caretOffset)

        val tBeforeInference = System.nanoTime()
        val prepared = requestInlineCandidates(
            snapshot = snapshot,
            request = autocompleteRequest,
            fimProvider = inlineProvider,
            nextEditProvider = inlineFallbackProvider,
        )
        val tAfterInference = System.nanoTime()
        if (prepared.isEmpty()) return null

        inlineCache.put(cacheKey, prepared, prefixTail = prefixTail)
        storeInlineState(editor, requestId, prepared)
        metricLatency(tStart, tAfterContext, tBeforeInference, tAfterInference, source = "provider")
        delayForDwell(requestStartedAt)
        return prepared.first()
    }

    suspend fun fetchSuggestionFlow(request: InlineCompletionRequest): Flow<String>? {
        val editor = request.editor

        pendingSuggestions.remove(editor)?.let { pending ->
            val requestId = requestSequence.incrementAndGet()
            storeInlineState(editor, requestId, listOf(pending))
            return flowOf(pending.text)
        }

        val settings = PluginSettings.getInstance().state
        if (!settings.autocompleteEnabled || !PluginSettings.getInstance().isConfigured) return null

        val tStart = System.nanoTime()
        val requestStartedAt = System.currentTimeMillis()
        delay(settings.debounceMs)

        val snapshot = ApplicationManager.getApplication().runReadAction<CompletionContextSnapshot?> {
            buildSnapshot(request)
        } ?: return null
        val tAfterContext = System.nanoTime()

        val triggerDecision = AutocompleteTriggerHeuristics.evaluate(
            documentText = snapshot.documentText,
            offset = snapshot.caretOffset,
            filePath = snapshot.filePath,
            maxDocumentChars = MAX_DOCUMENT_CHARS,
            lookbackForNonWhitespace = LOOKBACK_FOR_NON_WHITESPACE,
        )
        if (!triggerDecision.shouldRequest) {
            metric("inline.skip", "reason" to triggerDecision.reason)
            return null
        }

        ensureProviders()
        val stateHash = PluginSettings.getInstance().state.hashCode()
        val autocompleteRequest = buildAutocompleteRequest(snapshot)
        val requestId = requestSequence.incrementAndGet()

        launchNextEditFetch(
            editor = editor,
            autocompleteRequest = autocompleteRequest,
            snapshot = snapshot,
            requestId = requestId,
        )

        val inlineProvider = selectInlineProvider(snapshot)
        val inlineFallbackProvider = if (snapshot.isTerminal) null
            else nextEditProvider?.takeIf { AutocompleteCapability.INLINE in it.capabilities }
        if (inlineProvider == null && inlineFallbackProvider == null) return null

        val stablePrefix = snapshot.prefixWindow.dropLast(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.prefixWindow.length)
        )
        val stableSuffix = snapshot.suffixWindow.drop(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.suffixWindow.length)
        )
        val providerKey = if (snapshot.isTerminal) "terminal" else PluginSettings.getInstance().state.autocompleteProvider.name
        val cacheKey = InlineSuggestionCache.Key(
            providerKey = providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixStableHash = stablePrefix.hashCode(),
            suffixStableHash = stableSuffix.hashCode(),
        )

        val prefixTail = snapshot.prefixWindow.takeLast(PROXIMITY_PREFIX_TAIL_CHARS)

        inlineCache.get(cacheKey)?.let { cached ->
            val filtered = filterInlineCandidates(
                snapshot,
                prepareInlineCandidates(snapshot, cached, autocompleteRequest),
            )
            if (filtered.isNotEmpty()) {
                storeInlineState(editor, requestId, filtered)
                metric("inline.cache.hit", "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "cache")
                delayForDwell(requestStartedAt)
                return flowOf(filtered.first().text)
            }
        }

        inlineCache.getProximity(
            providerKey = PluginSettings.getInstance().state.autocompleteProvider.name,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixTail = prefixTail,
        )?.let { proxCandidates ->
            val filtered = filterInlineCandidates(snapshot, proxCandidates)
            if (filtered.isNotEmpty()) {
                storeInlineState(editor, requestId, filtered)
                metric("inline.cache.proximity_hit", "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "proximity")
                delayForDwell(requestStartedAt)
                return flowOf(filtered.first().text)
            }
        }
        metric("inline.cache.miss", "offset" to snapshot.caretOffset)

        val streamingFlow = inlineProvider?.completeStreaming(autocompleteRequest)
        if (streamingFlow != null) {
            return channelFlow {
                val accumulated = StringBuilder()
                val tBeforeInference = System.nanoTime()
                streamingFlow.collect { chunk ->
                    accumulated.append(chunk)
                    send(chunk)
                }
                val tAfterInference = System.nanoTime()
                val fullText = accumulated.toString().trim()
                if (fullText.isNotBlank()) {
                    val candidate = InlineCompletionCandidate(
                        text = fullText,
                        insertionOffset = snapshot.caretOffset,
                    )
                    inlineCache.put(cacheKey, listOf(candidate), prefixTail = prefixTail)
                    storeInlineState(editor, requestId, listOf(candidate))
                    metricLatency(tStart, tAfterContext, tBeforeInference, tAfterInference, source = "stream")
                }
            }
        }

        val tBeforeInference = System.nanoTime()
        val prepared = requestInlineCandidates(
            snapshot = snapshot,
            request = autocompleteRequest,
            fimProvider = inlineProvider,
            nextEditProvider = inlineFallbackProvider,
        )
        val tAfterInference = System.nanoTime()
        if (prepared.isEmpty()) return null

        inlineCache.put(cacheKey, prepared, prefixTail = prefixTail)
        storeInlineState(editor, requestId, prepared)
        metricLatency(tStart, tAfterContext, tBeforeInference, tAfterInference, source = "provider")
        delayForDwell(requestStartedAt)
        return flowOf(prepared.first().text)
    }

    fun canAcceptOnTab(editor: Editor): Boolean =
        inlineStates[editor]?.visible == true ||
            nextEditStates[editor]?.preview != null ||
            nextEditStates[editor]?.candidate != null

    fun canAcceptInline(editor: Editor): Boolean =
        inlineStates[editor]?.visible == true

    fun hasActiveSuggestion(editor: Editor): Boolean =
        inlineStates[editor]?.visible == true ||
            nextEditStates[editor]?.candidate != null ||
            nextEditStates[editor]?.preview != null

    fun hasAlternativeSuggestions(editor: Editor): Boolean =
        (inlineStates[editor]?.candidates?.size ?: 0) > 1

    fun acceptOnTab(editor: Editor): Boolean {
        val previewState = nextEditStates[editor]
        if (previewState?.preview != null) {
            suppressCaretHandling(editor) {
                previewState.preview.apply(project)
            }
            metric("next_edit.accepted", "start" to previewState.candidate.startOffset)
            clearNextEdit(editor)
            return true
        }

        if (inlineStates[editor]?.visible == true) {
            metric("inline.accepted", "offset" to editor.caretModel.offset)
            InlineCompletion.getHandlerOrNull(editor)?.insert()
            return true
        }

        val candidate = previewState?.candidate ?: return false
        val preview = NextEditPreview(editor, candidate)
        suppressCaretHandling(editor) {
            preview.show()
            preview.jumpToPreview()
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
        nextEditStates[editor] = previewState.copy(preview = preview)
        metric("next_edit.previewed", "start" to candidate.startOffset, "end" to candidate.endOffset)
        return true
    }

    fun acceptInline(editor: Editor): Boolean {
        if (inlineStates[editor]?.visible != true) return false
        metric("inline.accepted.inline_only", "offset" to editor.caretModel.offset)
        InlineCompletion.getHandlerOrNull(editor)?.insert()
        return true
    }

    fun acceptNextWord(editor: Editor): Boolean {
        val state = inlineStates[editor] ?: return false
        if (!state.visible) return false
        val candidate = state.candidates.getOrNull(0) ?: return false
        val text = candidate.text
        if (text.isBlank()) return false

        val wordEnd = findNextWordBoundary(text)
        val acceptedPart = text.substring(0, wordEnd)
        val remainder = text.substring(wordEnd)

        suppressCaretHandling(editor) {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(candidate.insertionOffset, acceptedPart)
                editor.caretModel.moveToOffset(candidate.insertionOffset + acceptedPart.length)
            }
        }
        InlineCompletion.getHandlerOrNull(editor)?.cancel()
        metric("inline.accept_word", "chars" to acceptedPart.length)

        if (remainder.isNotBlank()) {
            pendingSuggestions[editor] = InlineCompletionCandidate(
                text = remainder.trimStart(),
                insertionOffset = candidate.insertionOffset + acceptedPart.length,
            )
        }
        return true
    }

    fun acceptNextLine(editor: Editor): Boolean {
        val state = inlineStates[editor] ?: return false
        if (!state.visible) return false
        val candidate = state.candidates.getOrNull(0) ?: return false
        val text = candidate.text
        if (text.isBlank()) return false

        val lineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val acceptedPart = text.substring(0, lineEnd)
        val remainder = text.substring(lineEnd)

        suppressCaretHandling(editor) {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(candidate.insertionOffset, acceptedPart)
                editor.caretModel.moveToOffset(candidate.insertionOffset + acceptedPart.length)
            }
        }
        InlineCompletion.getHandlerOrNull(editor)?.cancel()
        metric("inline.accept_line", "chars" to acceptedPart.length)

        if (remainder.isNotBlank()) {
            pendingSuggestions[editor] = InlineCompletionCandidate(
                text = remainder,
                insertionOffset = candidate.insertionOffset + acceptedPart.length,
            )
        }
        return true
    }

    fun cycleToNextSuggestion(editor: Editor): Boolean = cycleSuggestion(editor, 1)

    fun cycleToPreviousSuggestion(editor: Editor): Boolean = cycleSuggestion(editor, -1)

    private fun cycleSuggestion(editor: Editor, step: Int): Boolean {
        val state = inlineStates[editor] ?: return false
        val candidates = state.candidates
        if (candidates.size <= 1) return false

        val currentIndex = state.currentIndex
        val nextIndex = ((currentIndex + step) % candidates.size + candidates.size) % candidates.size
        val nextCandidate = candidates[nextIndex]

        inlineStates[editor] = state.copy(currentIndex = nextIndex)
        pendingSuggestions[editor] = nextCandidate
        InlineCompletion.getHandlerOrNull(editor)?.cancel()
        metric("inline.cycle", "index" to nextIndex, "total" to candidates.size)
        return true
    }

    private fun findNextWordBoundary(text: String): Int {
        if (text.isEmpty()) return 0
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        if (i == 0) i = 1
        return i
    }

    fun rejectSuggestion(editor: Editor, dismissReason: DismissReason = DismissReason.ESCAPE): Boolean {
        var rejected = false

        pendingSuggestions.remove(editor)

        inlineStates.remove(editor)?.let { state ->
            state.rejectionKey?.let { rejectionCache.recordRejection(it, dismissReason) }
            InlineCompletion.getHandlerOrNull(editor)?.cancel()
            rejected = true
            metric("inline.rejected", "reason" to dismissReason)
        }

        if (clearNextEdit(editor)) {
            rejected = true
            metric("next_edit.rejected", "reason" to dismissReason)
        }

        return rejected
    }

    fun rejectSuggestion(dismissReason: DismissReason = DismissReason.ESCAPE): Boolean {
        val activeEditor = inlineStates.entries.firstOrNull { it.value.visible }?.key
            ?: nextEditStates.keys.firstOrNull()
            ?: return false
        return rejectSuggestion(activeEditor, dismissReason)
    }

    fun onCaretMoved(editor: Editor) {
        synchronized(caretSuppression) {
            if (caretSuppression.remove(editor)) return
        }
        clearNextEdit(editor)
    }

    fun onFocusLost(editor: Editor) {
        pendingSuggestions.remove(editor)
        clearNextEdit(editor)
    }

    fun onSelectionChanged(editor: Editor) {
        clearNextEdit(editor)
    }

    fun onDocumentChanged(
        editor: Editor,
        changeOffset: Int,
        oldLength: Int,
        newText: String,
    ): DocumentChangeResult {
        val nextEdit = nextEditStates[editor]
        if (nextEdit != null) {
            if (nextEdit.preview != null) {
                clearNextEdit(editor)
            } else {
                val editEnd = changeOffset + oldLength
                val candidate = nextEdit.candidate
                if (changeOffset < candidate.endOffset && editEnd > candidate.startOffset) {
                    clearNextEdit(editor)
                } else if (editEnd <= candidate.startOffset) {
                    val delta = newText.length - oldLength
                    if (delta != 0) {
                        nextEditStates[editor] = nextEdit.copy(
                            candidate = candidate.copy(
                                startOffset = candidate.startOffset + delta,
                                endOffset = candidate.endOffset + delta,
                            )
                        )
                    }
                }
            }
        }
        return DocumentChangeResult.REQUEST_COMPLETION
    }

    fun requestCompletion(editor: Editor, offset: Int, preserveCurrentSuggestion: Boolean = false) {
        if (!preserveCurrentSuggestion) {
            clearNextEdit(editor)
        }
        if (offset !in 0..editor.document.textLength) return
    }

    override fun dispose() {
        scope.cancel()
        fimProvider?.dispose()
        nextEditProvider?.dispose()
        terminalProvider?.dispose()
        pendingSuggestions.clear()
        inlineStates.keys.toList().forEach(::clearInlineState)
        nextEditStates.keys.toList().forEach(::clearNextEdit)
        inlineCache.clear()
    }

    enum class DocumentChangeResult {
        KEEP_SUGGESTION,
        REQUEST_COMPLETION,
    }

    enum class DismissReason {
        ESCAPE,
        CARET_MOVED,
        FOCUS_LOST,
        TYPING,
        SELECTION_CHANGED,
        ALTERNATIVE_REQUESTED,
    }

    private fun ensureProviders() {
        val settings = PluginSettings.getInstance().state
        val settingsHash = settings.hashCode()
        if (currentProviderType == settings.autocompleteProvider && currentSettingsHash == settingsHash) return

        fimProvider?.dispose()
        nextEditProvider?.dispose()
        terminalProvider?.dispose()
        fimProvider = AutocompleteProviderFactory.createFimProvider(settings)
        nextEditProvider = AutocompleteProviderFactory.createNextEditProvider(settings)
        terminalProvider = AutocompleteProviderFactory.createTerminalProvider(settings)
        currentProviderType = settings.autocompleteProvider
        currentSettingsHash = settingsHash
        inlineCache = createInlineCache()
    }

    private fun buildSnapshot(request: InlineCompletionRequest): CompletionContextSnapshot? {
        val editor = request.editor
        if (editor.project != project || editor.isViewer) return null
        if (editor.selectionModel.hasSelection()) return null

        val documentText = request.document.text
        val caretOffset = request.endOffset.coerceIn(0, documentText.length)
        if (caretOffset != editor.caretModel.offset) return null

        val isTerminal = TerminalDetector.isTerminalEditor(editor)
        val language = if (isTerminal) "shell" else request.file.language.id
        val filePath = if (isTerminal) null else request.file.virtualFile?.path
        val inlineContext = if (isTerminal) null else PsiInlineContextBuilder.build(
            project = project,
            document = request.document,
            documentText = documentText,
            caretOffset = caretOffset,
        )

        return CompletionContextSnapshot(
            filePath = filePath,
            language = language,
            documentText = documentText,
            documentStamp = request.document.modificationStamp,
            caretOffset = caretOffset,
            prefix = documentText.substring(0, caretOffset),
            suffix = documentText.substring(caretOffset),
            prefixWindow = documentText.substring(0, caretOffset).takeLast(CONTEXT_WINDOW_CHARS),
            suffixWindow = documentText.substring(caretOffset).take(CONTEXT_WINDOW_CHARS),
            inlineContext = inlineContext,
            project = project,
            isTerminal = isTerminal,
        )
    }

    private fun buildAutocompleteRequest(snapshot: CompletionContextSnapshot): AutocompleteRequest {
        val request = AutocompleteRequest(
            prefix = snapshot.prefix,
            suffix = snapshot.suffix,
            filePath = snapshot.filePath,
            language = snapshot.language?.takeIf { it.isNotBlank() },
            cursorOffset = snapshot.caretOffset,
            inlineContext = snapshot.inlineContext,
        )

        if (PluginSettings.getInstance().state.autocompleteProvider != AutocompleteProviderType.INCEPTION_LABS) {
            return request
        }

        val tracker = project.service<EditContextTracker>()
        snapshot.filePath?.let { filePath ->
            tracker.onFileViewed(
                filePath = filePath,
                content = snapshot.documentText,
                caretOffset = snapshot.caretOffset,
            )
        }
        return request.copy(
            recentlyViewedSnippets = tracker.recentSnippets.takeLast(MAX_NEXT_EDIT_SNIPPETS),
            editDiffHistory = tracker.recentDiffs.takeLast(MAX_NEXT_EDIT_DIFFS),
        )
    }

    private fun prepareInlineCandidates(
        snapshot: CompletionContextSnapshot,
        rawCandidates: List<InlineCompletionCandidate>,
        request: AutocompleteRequest,
    ): List<InlineCompletionCandidate> = InlineCandidatePreparation.prepare(
        rawCandidates = rawCandidates,
        request = request,
        snapshot = snapshot,
        maxSuggestionChars = MAX_SUGGESTION_CHARS,
    )

    private fun filterInlineCandidates(
        snapshot: CompletionContextSnapshot,
        candidates: List<InlineCompletionCandidate>,
    ): List<InlineCompletionCandidate> =
        candidates.filter { candidate ->
            if (candidate.insertionOffset !in 0..snapshot.documentText.length) return@filter false

            val suffix = snapshot.documentText.substring(candidate.insertionOffset)
            if (suffix.startsWith(candidate.text)) return@filter false

            val rejectionKey = suggestionKey(
                documentHash = snapshot.documentText.hashCode(),
                insertionOffset = candidate.insertionOffset,
                text = candidate.text,
            )
            rejectionCache.shouldShow(rejectionKey)
        }

    private suspend fun requestInlineCandidates(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider?,
        nextEditProvider: AutocompleteProvider?,
    ): List<InlineCompletionCandidate> {
        val selection = AutocompleteProviderCoordinator.selectInlineCandidates(
            snapshot = snapshot,
            request = request,
            fimProvider = fimProvider,
            nextEditProvider = nextEditProvider,
            onProviderFailure = { sourceName, error ->
                log.warn("Autocomplete request failed for $sourceName", error)
            },
            shouldShow = { candidate ->
                val rejectionKey = suggestionKey(
                    documentHash = snapshot.documentText.hashCode(),
                    insertionOffset = candidate.insertionOffset,
                    text = candidate.text,
                )
                rejectionCache.shouldShow(rejectionKey)
            },
        ) ?: return emptyList()

        metric("inline.source", "provider" to selection.sourceName)
        return selection.candidates
    }

    private fun storeInlineState(editor: Editor, requestId: Long, candidates: List<InlineCompletionCandidate>) {
        val primary = candidates.firstOrNull() ?: return
        val documentHash = editor.document.text.hashCode()
        inlineStates[editor] = InlineState(
            requestId = requestId,
            candidates = candidates,
            rejectionKey = suggestionKey(documentHash, primary.insertionOffset, primary.text),
        )
    }

    private fun onInlineShown(editor: Editor) {
        val current = inlineStates[editor] ?: return
        if (current.visible) return
        inlineStates[editor] = current.copy(
            visible = true,
            shownAt = System.currentTimeMillis(),
        )
        metric("inline.shown", "count" to current.candidates.size)
    }

    private fun clearInlineState(editor: Editor) {
        inlineStates.remove(editor)
    }

    private fun launchNextEditFetch(
        editor: Editor,
        autocompleteRequest: AutocompleteRequest,
        snapshot: CompletionContextSnapshot,
        requestId: Long,
    ) {
        val settings = PluginSettings.getInstance().state
        if (!settings.nextEditEnabled) {
            clearNextEdit(editor)
            return
        }

        val provider = nextEditProvider?.takeIf { AutocompleteCapability.NEXT_EDIT in it.capabilities } ?: return
        scope.launch {
            val response = try {
                provider.complete(autocompleteRequest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Next edit request failed", e)
                null
            } ?: return@launch

            val candidate = response.nextEditCandidates
                .firstNotNullOfOrNull { nextEditCandidateOrNull(it, snapshot) }

            withContext(Dispatchers.Main) {
                if (editor.isDisposed) return@withContext
                if (inlineStates[editor]?.requestId?.let { it > requestId } == true) return@withContext

                if (candidate == null) {
                    clearNextEdit(editor)
                    return@withContext
                }

                nextEditStates[editor] = NextEditState(
                    requestId = requestId,
                    candidate = candidate,
                )
                metric("next_edit.ready", "start" to candidate.startOffset, "end" to candidate.endOffset)
            }
        }
    }

    private fun nextEditCandidateOrNull(
        candidate: NextEditCompletionCandidate,
        snapshot: CompletionContextSnapshot,
    ): NextEditCompletionCandidate? = NextEditCandidatePolicy.previewCandidateOrNull(
        candidate = candidate,
        snapshot = snapshot,
        maxPreviewLines = PluginSettings.getInstance().state.nextEditPreviewMaxLines,
        maxPreviewChars = MAX_NEXT_EDIT_CHARS,
    )

    private fun clearNextEdit(editor: Editor): Boolean {
        val removed = nextEditStates.remove(editor) ?: return false
        removed.preview?.dispose()
        return true
    }

    private fun suggestionKey(
        documentHash: Int,
        insertionOffset: Int,
        text: String,
    ): RejectionCache.SuggestionKey = RejectionCache.SuggestionKey(
        documentHash = documentHash,
        offset = insertionOffset,
        suggestionHash = text.hashCode(),
    )

    private suspend fun delayForDwell(requestStartedAt: Long) {
        val remaining = PluginSettings.getInstance().state.tabAcceptMinDwellMs -
            (System.currentTimeMillis() - requestStartedAt)
        if (remaining > 0) {
            delay(remaining)
        }
    }

    private fun metricLatency(
        tStart: Long,
        tAfterContext: Long,
        tBeforeInference: Long = 0,
        tAfterInference: Long = 0,
        source: String,
    ) {
        val now = System.nanoTime()
        metric(
            "inline.latency",
            "total_ms" to (now - tStart) / 1_000_000,
            "context_ms" to (tAfterContext - tStart) / 1_000_000,
            "inference_ms" to if (tBeforeInference > 0) (tAfterInference - tBeforeInference) / 1_000_000 else 0,
            "source" to source,
        )
    }

    private fun metric(name: String, vararg pairs: Pair<String, Any?>) {
        if (!PluginSettings.getInstance().state.debugMetricsLogging) return
        val body = pairs.joinToString(", ") { (key, value) -> "$key=$value" }
        if (body.isBlank()) {
            log.info("[ood-metrics] $name")
        } else {
            log.info("[ood-metrics] $name $body")
        }
    }

    private fun createInlineCache(): InlineSuggestionCache {
        val settings = PluginSettings.getInstance().state
        return InlineSuggestionCache(
            maxEntries = settings.suggestionCacheMaxEntries,
            ttlMs = settings.suggestionCacheTtlMs,
        )
    }

    private fun selectInlineProvider(snapshot: CompletionContextSnapshot): AutocompleteProvider? {
        if (snapshot.isTerminal) {
            return terminalProvider ?: fimProvider?.takeIf { AutocompleteCapability.INLINE in it.capabilities }
        }
        return fimProvider?.takeIf { AutocompleteCapability.INLINE in it.capabilities }
    }

    private fun suppressCaretHandling(editor: Editor, block: () -> Unit) {
        synchronized(caretSuppression) {
            caretSuppression.add(editor)
        }
        try {
            block()
        } finally {
            synchronized(caretSuppression) {
                caretSuppression.remove(editor)
            }
        }
    }

    private data class InlineState(
        val requestId: Long,
        val candidates: List<InlineCompletionCandidate>,
        val rejectionKey: RejectionCache.SuggestionKey?,
        val visible: Boolean = false,
        val shownAt: Long = 0L,
        val currentIndex: Int = 0,
    )

    private data class NextEditState(
        val requestId: Long,
        val candidate: NextEditCompletionCandidate,
        val preview: NextEditPreview? = null,
    )

    companion object {
        private const val CONTEXT_WINDOW_CHARS = 1_500
        private const val CACHE_STABILITY_MARGIN = 20
        private const val PROXIMITY_PREFIX_TAIL_CHARS = 60
        private const val MAX_DOCUMENT_CHARS = 250_000
        private const val MAX_SUGGESTION_CHARS = 400
        private const val LOOKBACK_FOR_NON_WHITESPACE = 80
        private const val MAX_NEXT_EDIT_SNIPPETS = 5
        private const val MAX_NEXT_EDIT_DIFFS = 5
        private const val MAX_NEXT_EDIT_CHARS = 800
    }
}
