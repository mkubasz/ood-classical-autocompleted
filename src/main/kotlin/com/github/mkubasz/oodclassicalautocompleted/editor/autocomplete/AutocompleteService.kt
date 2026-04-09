package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineMode
import com.github.mkubasz.oodclassicalautocompleted.completion.pipeline.CompletionEngine
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.ModelProviderCoordinator
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.ProviderRegistry
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class AutocompleteService(private val project: Project) : Disposable {

    private val log = logger<AutocompleteService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = SuggestionSessionStore()
    private val providerRegistry = ProviderRegistry()
    private val metrics = AutocompleteMetrics(
        isEnabled = { PluginSettings.getInstance().state.debugMetricsLogging },
        sink = { event -> log.info("[ood-metrics] ${event.formatForLog()}") },
    )
    private val inlineArtifactPostProcessor = InlineArtifactPostProcessor(
        optionsProvider = { inlinePreparationOptions() },
        shouldShow = { snapshot, candidate ->
            if (candidate.insertionOffset !in 0..snapshot.documentText.length) {
                false
            } else {
                val suffix = snapshot.documentText.substring(candidate.insertionOffset)
                if (suffix.startsWith(candidate.text)) {
                    false
                } else {
                    val rejectionKey = suggestionKey(
                        documentHash = snapshot.documentText.hashCode(),
                        insertionOffset = candidate.insertionOffset,
                        text = candidate.text,
                    )
                    rejectionCache.shouldShow(rejectionKey)
                }
            }
        },
    )
    private val completionEngine = CompletionEngine(
        project = project,
        postProcessors = listOf(inlineArtifactPostProcessor),
    )
    private val inlineSessionController = InlineSessionController(
        project = project,
        scope = scope,
        sessions = sessions,
        metric = { type, pairs -> metric(type, *pairs) },
        recordAcceptedSuggestion = ::recordAcceptedSuggestion,
    )
    private val nextEditController = NextEditController(
        project = project,
        scope = scope,
        sessions = sessions,
        providerRegistry = providerRegistry,
        completionEngine = completionEngine,
        metric = { type, pairs -> metric(type, *pairs) },
        onProviderFailure = { sourceName, error ->
            log.warn("Next edit request failed for $sourceName", error)
        },
        recordAcceptedSuggestion = ::recordAcceptedSuggestion,
    )
    private val requestSequence = AtomicLong(0)

    private val rejectionCache: RejectionCache
        get() = sessions.rejectionCache
    private val inlineStates
        get() = sessions.inlineStates
    private val nextEditStates
        get() = sessions.nextEditStates
    private val pendingSuggestions
        get() = sessions.pendingSuggestions
    private val latestRequestIds
        get() = sessions.latestRequestIds
    private val acceptedSuggestionTrackers
        get() = sessions.acceptedSuggestionTrackers
    private val inlineCache: InlineSuggestionCache
        get() = sessions.inlineCache()

    val hasActiveSuggestion: Boolean
        get() = inlineStates.values.any { it.visible } || nextEditStates.isNotEmpty()

    fun installInlineSupport(editor: Editor) {
        inlineSessionController.installInlineSupport(editor)
    }

    fun unregisterEditor(editor: Editor) {
        latestRequestIds.remove(editor)
        nextEditController.unregisterEditor(editor)
        inlineSessionController.unregisterEditor(editor)
    }

    suspend fun fetchInlineCandidate(request: InlineCompletionRequest): InlineCompletionCandidate? {
        val settings = PluginSettings.getInstance().state
        val pluginSettings = PluginSettings.getInstance()
        val isTerminal = TerminalDetector.isTerminalEditor(request.editor)
        if (!settings.autocompleteEnabled || !pluginSettings.hasInlineCapabilityConfigured(isTerminal)) return null

        val tStart = System.nanoTime()
        val requestStartedAt = System.currentTimeMillis()
        delay(settings.debounceMs)
        val editor = request.editor
        val requestId = requestSequence.incrementAndGet()
        providerRegistry.ensureCurrent()
        sessions.ensureInlineCache(settings)
        val bundleResult = completionEngine.buildBundle(request, requestId) ?: return null
        val bundle = when (bundleResult) {
            is CompletionEngine.BundleResult.Skip -> {
                metricPipelineTrace(bundleResult.trace, PipelineMode.INLINE)
                metric(
                    AutocompleteMetricType.INLINE_CONTEXT,
                    "source" to bundleResult.inlineContextSource.orEmpty().ifBlank { "none" },
                    "has_context" to false,
                )
                metric(AutocompleteMetricType.INLINE_SKIP, "reason" to bundleResult.facts.skipReason)
                return null
            }

            is CompletionEngine.BundleResult.Ready -> {
                metricPipelineTrace(bundleResult.bundle.trace, PipelineMode.INLINE)
                metric(
                    AutocompleteMetricType.INLINE_CONTEXT,
                    "source" to bundleResult.bundle.contribution.inlineContextSource.orEmpty().ifBlank { "none" },
                    "has_context" to (bundleResult.bundle.contribution.inlineContext != null),
                )
                bundleResult.bundle
            }
        }
        val snapshot = completionEngine.toCompletionContextSnapshot(bundle)
        val pipelineRequest = completionEngine.buildRequest(bundle, PipelineMode.INLINE)
        val tAfterContext = System.nanoTime()
        metricRetrieval(PipelineMode.INLINE, pipelineRequest.retrievedChunks.size)

        latestRequestIds[editor] = requestId
        nextEditController.launchNextEditFetch(editor = editor, bundle = bundle, requestId = requestId)

        val providerSelection = providerRegistry.inlineSelection(bundle.snapshot.isTerminal)
        if (providerSelection.primary == null && providerSelection.fallback == null) return null

        val stateHash = settings.hashCode()
        val stablePrefix = snapshot.prefixWindow.dropLast(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.prefixWindow.length)
        )
        val stableSuffix = snapshot.suffixWindow.drop(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.suffixWindow.length)
        )
        val cacheKey = InlineSuggestionCache.Key(
            providerKey = providerSelection.providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixStableHash = stablePrefix.hashCode(),
            suffixStableHash = stableSuffix.hashCode(),
        )

        val prefixTail = snapshot.prefixWindow.takeLast(PROXIMITY_PREFIX_TAIL_CHARS)

        inlineCache.get(cacheKey)?.let { cached ->
            val filtered = inlineArtifactPostProcessor.filterPrepared(pipelineRequest, cached)
            if (filtered.isNotEmpty()) {
                inlineSessionController.storeInlineState(editor, requestId, filtered)
                metric(AutocompleteMetricType.INLINE_CACHE_HIT, "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "cache")
                delayForDwell(requestStartedAt)
                return filtered.first()
            }
        }

        inlineCache.getProximity(
            providerKey = providerSelection.providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixTail = prefixTail,
        )?.let { proxCandidates ->
            val filtered = inlineArtifactPostProcessor.filterPrepared(pipelineRequest, proxCandidates)
            if (filtered.isNotEmpty()) {
                inlineSessionController.storeInlineState(editor, requestId, filtered)
                metric(AutocompleteMetricType.INLINE_CACHE_PROXIMITY_HIT, "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "proximity")
                delayForDwell(requestStartedAt)
                return filtered.first()
            }
        }
        metric(AutocompleteMetricType.INLINE_CACHE_MISS, "offset" to snapshot.caretOffset)

        val tBeforeInference = System.nanoTime()
        val artifact = ModelProviderCoordinator.completeInline(
            call = ModelCall(
                providerId = providerSelection.primary?.id
                    ?: providerSelection.fallback?.id
                    ?: "inline",
                request = pipelineRequest,
            ),
            primary = providerSelection.primary,
            fallback = providerSelection.fallback,
            onFailure = { sourceName, error ->
                log.warn("Autocomplete request failed for $sourceName", error)
            },
        )?.let { completionEngine.applyPostProcessors(pipelineRequest, it) }
        val tAfterInference = System.nanoTime()
        val prepared = artifact?.inlineCandidates.orEmpty()
        if (prepared.isEmpty()) return null

        inlineCache.put(cacheKey, prepared, prefixTail = prefixTail)
        inlineSessionController.storeInlineState(editor, requestId, prepared)
        metric(AutocompleteMetricType.INLINE_SOURCE, "provider" to artifact?.sourceName)
        metricLatency(tStart, tAfterContext, tBeforeInference, tAfterInference, source = "provider")
        delayForDwell(requestStartedAt)
        return prepared.first()
    }

    suspend fun fetchSuggestionFlow(request: InlineCompletionRequest): Flow<String>? {
        val editor = request.editor

        inlineSessionController.takePendingSuggestion(editor)?.let { pending ->
            val requestId = requestSequence.incrementAndGet()
            inlineSessionController.storeInlineState(editor, requestId, listOf(pending))
            return flowOf(pending.text)
        }

        val settings = PluginSettings.getInstance().state
        val pluginSettings = PluginSettings.getInstance()
        val isTerminal = TerminalDetector.isTerminalEditor(editor)
        if (!settings.autocompleteEnabled || !pluginSettings.hasInlineCapabilityConfigured(isTerminal)) return null

        val tStart = System.nanoTime()
        val requestStartedAt = System.currentTimeMillis()
        delay(settings.debounceMs)
        val requestId = requestSequence.incrementAndGet()
        providerRegistry.ensureCurrent()
        sessions.ensureInlineCache(settings)
        val bundleResult = completionEngine.buildBundle(request, requestId) ?: return null
        val bundle = when (bundleResult) {
            is CompletionEngine.BundleResult.Skip -> {
                metricPipelineTrace(bundleResult.trace, PipelineMode.INLINE)
                metric(
                    AutocompleteMetricType.INLINE_CONTEXT,
                    "source" to bundleResult.inlineContextSource.orEmpty().ifBlank { "none" },
                    "has_context" to false,
                )
                metric(AutocompleteMetricType.INLINE_SKIP, "reason" to bundleResult.facts.skipReason)
                return null
            }

            is CompletionEngine.BundleResult.Ready -> {
                metricPipelineTrace(bundleResult.bundle.trace, PipelineMode.INLINE)
                metric(
                    AutocompleteMetricType.INLINE_CONTEXT,
                    "source" to bundleResult.bundle.contribution.inlineContextSource.orEmpty().ifBlank { "none" },
                    "has_context" to (bundleResult.bundle.contribution.inlineContext != null),
                )
                bundleResult.bundle
            }
        }
        val snapshot = completionEngine.toCompletionContextSnapshot(bundle)
        val pipelineRequest = completionEngine.buildRequest(bundle, PipelineMode.INLINE)
        val tAfterContext = System.nanoTime()
        metricRetrieval(PipelineMode.INLINE, pipelineRequest.retrievedChunks.size)

        latestRequestIds[editor] = requestId
        nextEditController.launchNextEditFetch(editor = editor, bundle = bundle, requestId = requestId)

        val providerSelection = providerRegistry.inlineSelection(bundle.snapshot.isTerminal)
        if (providerSelection.primary == null && providerSelection.fallback == null) return null

        val stateHash = settings.hashCode()
        val stablePrefix = snapshot.prefixWindow.dropLast(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.prefixWindow.length)
        )
        val stableSuffix = snapshot.suffixWindow.drop(
            CACHE_STABILITY_MARGIN.coerceAtMost(snapshot.suffixWindow.length)
        )
        val cacheKey = InlineSuggestionCache.Key(
            providerKey = providerSelection.providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixStableHash = stablePrefix.hashCode(),
            suffixStableHash = stableSuffix.hashCode(),
        )

        val prefixTail = snapshot.prefixWindow.takeLast(PROXIMITY_PREFIX_TAIL_CHARS)

        inlineCache.get(cacheKey)?.let { cached ->
            val filtered = inlineArtifactPostProcessor.filterPrepared(pipelineRequest, cached)
            if (filtered.isNotEmpty()) {
                inlineSessionController.storeInlineState(editor, requestId, filtered)
                metric(AutocompleteMetricType.INLINE_CACHE_HIT, "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "cache")
                delayForDwell(requestStartedAt)
                return flowOf(filtered.first().text)
            }
        }

        inlineCache.getProximity(
            providerKey = providerSelection.providerKey,
            settingsHash = stateHash,
            filePath = snapshot.filePath,
            prefixTail = prefixTail,
        )?.let { proxCandidates ->
            val filtered = inlineArtifactPostProcessor.filterPrepared(pipelineRequest, proxCandidates)
            if (filtered.isNotEmpty()) {
                inlineSessionController.storeInlineState(editor, requestId, filtered)
                metric(AutocompleteMetricType.INLINE_CACHE_PROXIMITY_HIT, "count" to filtered.size)
                metricLatency(tStart, tAfterContext, source = "proximity")
                delayForDwell(requestStartedAt)
                return flowOf(filtered.first().text)
            }
        }
        metric(AutocompleteMetricType.INLINE_CACHE_MISS, "offset" to snapshot.caretOffset)

        val primaryProvider = providerSelection.primary
        val streamingFlow = primaryProvider?.completeStreaming(
            ModelCall(
                providerId = primaryProvider.id,
                request = pipelineRequest,
            )
        )
        if (streamingFlow != null) {
            return streamPreparedSuggestion(
                editor = editor,
                snapshot = snapshot,
                pipelineRequest = pipelineRequest,
                streamingFlow = streamingFlow,
                cacheKey = cacheKey,
                prefixTail = prefixTail,
                requestId = requestId,
                requestStartedAt = requestStartedAt,
                tStart = tStart,
                tAfterContext = tAfterContext,
            )
        }

        val tBeforeInference = System.nanoTime()
        val artifact = ModelProviderCoordinator.completeInline(
            call = ModelCall(
                providerId = providerSelection.primary?.id
                    ?: providerSelection.fallback?.id
                    ?: "inline",
                request = pipelineRequest,
            ),
            primary = providerSelection.primary,
            fallback = providerSelection.fallback,
            onFailure = { sourceName, error ->
                log.warn("Autocomplete request failed for $sourceName", error)
            },
        )?.let { completionEngine.applyPostProcessors(pipelineRequest, it) }
        val tAfterInference = System.nanoTime()
        val prepared = artifact?.inlineCandidates.orEmpty()
        if (prepared.isEmpty()) return null

        inlineCache.put(cacheKey, prepared, prefixTail = prefixTail)
        inlineSessionController.storeInlineState(editor, requestId, prepared)
        metric(AutocompleteMetricType.INLINE_SOURCE, "provider" to artifact?.sourceName)
        metricLatency(tStart, tAfterContext, tBeforeInference, tAfterInference, source = "provider")
        delayForDwell(requestStartedAt)
        return flowOf(prepared.first().text)
    }

    fun canAcceptOnTab(editor: Editor): Boolean =
        inlineSessionController.hasVisibleInline(editor) || nextEditController.canAcceptOnTab(editor)

    fun canAcceptInline(editor: Editor): Boolean =
        inlineSessionController.hasVisibleInline(editor)

    fun hasActiveSuggestion(editor: Editor): Boolean =
        inlineSessionController.hasVisibleInline(editor) || nextEditController.hasActiveSuggestion(editor)

    fun hasAlternativeSuggestions(editor: Editor): Boolean =
        inlineSessionController.hasAlternativeSuggestions(editor)

    fun acceptOnTab(editor: Editor): Boolean {
        val nextEditState = nextEditStates[editor]
        if (nextEditState?.preview != null) return nextEditController.acceptOnTab(editor)
        if (inlineSessionController.hasVisibleInline(editor)) return inlineSessionController.acceptOnTab(editor)
        return nextEditController.acceptOnTab(editor)
    }

    fun acceptInline(editor: Editor): Boolean {
        return inlineSessionController.acceptInline(editor)
    }

    fun acceptNextWord(editor: Editor): Boolean {
        return inlineSessionController.acceptNextWord(editor)
    }

    fun acceptNextLine(editor: Editor): Boolean {
        return inlineSessionController.acceptNextLine(editor)
    }

    fun cycleToNextSuggestion(editor: Editor): Boolean = cycleSuggestion(editor, 1)

    fun cycleToPreviousSuggestion(editor: Editor): Boolean = cycleSuggestion(editor, -1)

    private fun cycleSuggestion(editor: Editor, step: Int): Boolean {
        return inlineSessionController.cycle(editor, step)
    }

    fun rejectSuggestion(editor: Editor, dismissReason: DismissReason = DismissReason.ESCAPE): Boolean {
        val rejectedInline = inlineSessionController.rejectInline(editor, dismissReason)
        val rejectedNextEdit = nextEditController.reject(editor, dismissReason)
        return rejectedInline || rejectedNextEdit
    }

    fun rejectSuggestion(dismissReason: DismissReason = DismissReason.ESCAPE): Boolean {
        val activeEditor = inlineStates.entries.firstOrNull { it.value.visible }?.key
            ?: nextEditStates.keys.firstOrNull()
            ?: return false
        return rejectSuggestion(activeEditor, dismissReason)
    }

    fun onCaretMoved(editor: Editor) {
        nextEditController.onCaretMoved(editor)
    }

    fun onFocusLost(editor: Editor) {
        inlineSessionController.onFocusLost(editor)
        nextEditController.onFocusLost(editor)
    }

    fun onSelectionChanged(editor: Editor) {
        nextEditController.onSelectionChanged(editor)
    }

    fun onDocumentChanged(
        editor: Editor,
        changeOffset: Int,
        oldLength: Int,
        newText: String,
    ): DocumentChangeResult {
        acceptedSuggestionTrackers[editor]
            ?.onDocumentChanged(changeOffset = changeOffset, oldLength = oldLength, newText = newText)
            ?.let { reverted ->
                metric(
                    AutocompleteMetricType.ACCEPTED_TEXT_REVERTED,
                    "kind" to reverted.kind.metricValue,
                    "deleted_chars" to reverted.deletedChars,
                    "age_ms" to reverted.ageMs,
                )
            }

        nextEditController.onDocumentChanged(editor, changeOffset, oldLength, newText)
        return DocumentChangeResult.REQUEST_COMPLETION
    }

    fun requestCompletion(editor: Editor, offset: Int, preserveCurrentSuggestion: Boolean = false) {
        if (offset !in 0..editor.document.textLength) return
        nextEditController.requestCompletion(editor, preserveCurrentSuggestion)
    }

    override fun dispose() {
        scope.cancel()
        providerRegistry.dispose()
        sessions.clear()
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

    private fun streamPreparedSuggestion(
        editor: Editor,
        snapshot: CompletionContextSnapshot,
        pipelineRequest: com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest,
        streamingFlow: Flow<String>,
        cacheKey: InlineSuggestionCache.Key,
        prefixTail: String,
        requestId: Long,
        requestStartedAt: Long,
        tStart: Long,
        tAfterContext: Long,
    ): Flow<String> = channelFlow {
        val accumulated = StringBuilder()
        val tBeforeInference = System.nanoTime()
        var renderedText = ""
        var dwellSatisfied = false
        var completed = false
        metric(AutocompleteMetricType.INLINE_STREAM_STARTED, "offset" to snapshot.caretOffset)

        suspend fun renderCandidate(candidate: InlineCompletionCandidate) {
            val nextText = candidate.text
            if (nextText.isBlank()) return
            if (!dwellSatisfied) {
                delayForDwell(requestStartedAt)
                dwellSatisfied = true
            }
            inlineSessionController.storeInlineState(editor, requestId, listOf(candidate))

            if (nextText.length <= renderedText.length) {
                renderedText = renderedText.take(nextText.length)
                return
            }
            if (!nextText.startsWith(renderedText)) {
                return
            }

            val delta = nextText.removePrefix(renderedText)
            if (delta.isNotEmpty()) {
                send(delta)
                renderedText = nextText
            }
        }

        suspend fun preparedCandidate(): InlineCompletionCandidate? {
            val candidate = InlineCompletionCandidate(
                text = accumulated.toString(),
                insertionOffset = snapshot.caretOffset,
            )
            return inlineArtifactPostProcessor.prepare(
                request = pipelineRequest,
                rawCandidates = listOf(candidate),
            ).firstOrNull()
        }

        try {
            streamingFlow.collect { chunk ->
                accumulated.append(chunk)
                preparedCandidate()?.let { renderCandidate(it) }
            }
            completed = true
        } catch (e: CancellationException) {
            metric(AutocompleteMetricType.INLINE_STREAM_CANCELLED, "offset" to snapshot.caretOffset)
            throw e
        }

        val finalCandidate = preparedCandidate()
        finalCandidate?.let { candidate ->
            renderCandidate(candidate)
            inlineCache.put(cacheKey, listOf(candidate), prefixTail = prefixTail)
            metricLatency(
                tStart = tStart,
                tAfterContext = tAfterContext,
                tBeforeInference = tBeforeInference,
                tAfterInference = System.nanoTime(),
                source = "stream",
            )
        }
        if (completed) {
            metric(
                AutocompleteMetricType.INLINE_STREAM_COMPLETED,
                "chars" to accumulated.length,
                "rendered_chars" to renderedText.length,
            )
        }
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
            AutocompleteMetricType.INLINE_LATENCY,
            "total_ms" to (now - tStart) / 1_000_000,
            "context_ms" to (tAfterContext - tStart) / 1_000_000,
            "inference_ms" to if (tBeforeInference > 0) (tAfterInference - tBeforeInference) / 1_000_000 else 0,
            "source" to source,
        )
    }

    private fun metric(type: AutocompleteMetricType, vararg pairs: Pair<String, Any?>) {
        metrics.record(type, *pairs)
    }

    private fun metricPipelineTrace(
        trace: com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineTrace,
        mode: PipelineMode,
    ) {
        trace.stageTimings.forEach { timing ->
            metric(
                AutocompleteMetricType.PIPELINE_STAGE,
                "mode" to mode.name.lowercase(),
                "stage" to timing.stage,
                "elapsed_ms" to timing.elapsedMs,
            )
        }
        trace.contributorTimings.forEach { timing ->
            metric(
                AutocompleteMetricType.PIPELINE_STAGE,
                "mode" to mode.name.lowercase(),
                "stage" to "context_contributor",
                "contributor" to timing.name,
                "elapsed_ms" to timing.elapsedMs,
                "blocks" to timing.emittedBlocks,
            )
        }
    }

    private fun recordAcceptedSuggestion(
        editor: Editor,
        kind: AcceptedSuggestionKind,
        startOffset: Int,
        insertedText: String,
    ) {
        acceptedSuggestionTrackers
            .computeIfAbsent(editor) { AcceptedSuggestionTracker() }
            .record(
                kind = kind,
                startOffset = startOffset,
                insertedText = insertedText,
            )
    }

    private fun inlinePreparationOptions(): InlineCandidatePreparation.Options {
        val settings = PluginSettings.getInstance().state
        return InlineCandidatePreparation.Options(
            minConfidenceScore = settings.minConfidenceScore,
            correctnessFilterEnabled = settings.correctnessFilterEnabled,
            onCorrectnessResult = { result ->
                val fields = mutableListOf<Pair<String, Any?>>(
                    "language_family" to result.family.metricValue,
                    "result" to when (result) {
                        is InlineCorrectnessFilter.Result.Pass -> "pass"
                        is InlineCorrectnessFilter.Result.Reject -> "reject"
                        is InlineCorrectnessFilter.Result.Timeout -> "timeout"
                    },
                )
                when (result) {
                    is InlineCorrectnessFilter.Result.Pass -> Unit
                    is InlineCorrectnessFilter.Result.Timeout -> {
                        fields += "reason" to InlineCorrectnessFilter.FailureReason.TIMEOUT.metricValue
                    }
                    is InlineCorrectnessFilter.Result.Reject -> {
                        fields += "reason" to result.reason.metricValue
                        fields += "syntax_errors" to result.syntaxErrors
                        fields += "unresolved_refs" to result.unresolvedReferences
                    }
                }
                metric(AutocompleteMetricType.INLINE_CORRECTNESS, *fields.toTypedArray())
            },
        )
    }

    private fun metricRetrieval(mode: PipelineMode, chunkCount: Int) {
        metric(
            if (chunkCount == 0) AutocompleteMetricType.RETRIEVAL_MISS else AutocompleteMetricType.RETRIEVAL_HIT,
            "mode" to mode.name.lowercase(),
            "chunks" to chunkCount,
        )
    }

    @TestOnly
    internal fun testingSeedInlineState(
        editor: Editor,
        candidates: List<InlineCompletionCandidate>,
        requestId: Long = 1L,
        visible: Boolean = true,
        shownAt: Long = 0L,
        currentIndex: Int = 0,
    ) {
        inlineSessionController.testingSeedInlineState(
            editor = editor,
            candidates = candidates,
            requestId = requestId,
            visible = visible,
            shownAt = shownAt,
            currentIndex = currentIndex,
        )
    }

    @TestOnly
    internal fun testingSeedNextEditState(
        editor: Editor,
        candidate: NextEditCompletionCandidate,
        requestId: Long = 1L,
    ) {
        nextEditController.testingSeedNextEditState(editor, candidate, requestId)
    }

    @TestOnly
    internal fun testingInlineState(editor: Editor): TestingInlineState? =
        inlineSessionController.testingInlineState(editor)

    @TestOnly
    internal fun testingPendingSuggestion(editor: Editor): InlineCompletionCandidate? =
        pendingSuggestions[editor]

    @TestOnly
    internal fun testingHasNextEditPreview(editor: Editor): Boolean =
        nextEditController.testingHasNextEditPreview(editor)

    internal data class TestingInlineState(
        val visible: Boolean,
        val currentIndex: Int,
        val candidates: List<InlineCompletionCandidate>,
    )

    companion object {
        private const val CACHE_STABILITY_MARGIN = 20
        private const val PROXIMITY_PREFIX_TAIL_CHARS = 60
    }
}
