package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProvider
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProviderFactory
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class AutocompleteService(private val project: Project) : Disposable {

    private val log = logger<AutocompleteService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentRenderer = AtomicReference<GhostTextRenderer?>(null)
    private val rejectionCache = RejectionCache()
    private var fimProvider: AutocompleteProvider? = null
    private var nextEditProvider: AutocompleteProvider? = null
    private var currentProviderType: AutocompleteProviderType? = null
    private var pendingJob: Job? = null
    private val requestSequence = AtomicLong(0)

    val hasActiveSuggestion: Boolean
        get() = currentRenderer.get()?.isActive == true

    fun requestCompletion(editor: Editor, offset: Int) {
        val settings = PluginSettings.getInstance()
        if (!settings.state.autocompleteEnabled) {
            log.debug("Autocomplete disabled in settings")
            clearSuggestion()
            return
        }
        if (!settings.isConfigured) {
            log.debug("Autocomplete provider is not configured")
            clearSuggestion()
            return
        }
        if (!shouldRequestCompletion(editor, offset)) {
            clearSuggestion()
            return
        }

        pendingJob?.cancel()

        val debounceMs = settings.state.debounceMs
        val requestId = requestSequence.incrementAndGet()
        val documentStamp = editor.document.modificationStamp
        pendingJob = scope.launch {
            delay(debounceMs)
            if (!isActive) return@launch

            if (editor.isDisposed) return@launch
            if (!shouldShowForCurrentState(editor, offset, documentStamp, requestId)) {
                log.debug("Stale request: stamp or sequence mismatch")
                return@launch
            }
            val request = ApplicationManager.getApplication().runReadAction<AutocompleteRequest?> {
                buildRequest(editor, offset)
            }
            if (request == null) {
                log.info("buildRequest returned null at offset=$offset")
                return@launch
            }

            ensureProviders()
            if (fimProvider == null && nextEditProvider == null) {
                log.debug("No autocomplete providers are available for the current settings")
                return@launch
            }

            val result = AutocompleteProviderCoordinator.complete(
                request = request,
                fimProvider = fimProvider,
                nextEditProvider = nextEditProvider,
            )
            if (result == null) {
                log.debug("All autocomplete providers returned null")
                return@launch
            }
            val suggestion = if (result.isExactInsertion) {
                result.text.trimEnd().take(MAX_SUGGESTION_CHARS)
            } else {
                AutocompleteSuggestionNormalizer.normalize(
                    rawText = result.text,
                    request = request,
                    maxChars = MAX_SUGGESTION_CHARS,
                )
            }
            val insertionOffset = result.insertionOffset ?: offset

            if (suggestion.isBlank()) return@launch
            withContext(Dispatchers.Main) {
                if (!editor.isDisposed && shouldShowForCurrentState(editor, offset, documentStamp, requestId)) {
                    showSuggestion(editor, offset, insertionOffset, suggestion)
                }
            }
        }
    }

    fun acceptSuggestion(editor: Editor): Boolean {
        val renderer = currentRenderer.get() ?: return false
        if (!renderer.isActive) return false
        if (renderer.editor !== editor) return false
        if (editor.caretModel.offset != renderer.anchorOffset) {
            clearSuggestion()
            return false
        }

        renderer.apply(project, editor)
        clearSuggestion()
        return true
    }

    fun rejectSuggestion(dismissReason: DismissReason = DismissReason.ESCAPE): Boolean {
        val renderer = currentRenderer.get() ?: return false
        if (!renderer.isActive) return false

        rejectionCache.recordRejection(renderer.suggestionKey, dismissReason)
        clearSuggestion()
        return true
    }

    fun onCaretMoved(editor: Editor) {
        val renderer = currentRenderer.get() ?: return
        if (renderer.editor !== editor) return
        if (editor.caretModel.offset != renderer.anchorOffset) {
            rejectSuggestion(DismissReason.CARET_MOVED)
        }
    }

    fun onFocusLost(editor: Editor) {
        val renderer = currentRenderer.get() ?: return
        if (renderer.editor === editor) {
            rejectSuggestion(DismissReason.FOCUS_LOST)
        }
    }

    fun onTyping(editor: Editor) {
        val renderer = currentRenderer.get() ?: return
        if (renderer.editor === editor) {
            rejectSuggestion(DismissReason.TYPING)
        }
    }

    fun onSelectionChanged(editor: Editor) {
        pendingJob?.cancel()
        val renderer = currentRenderer.get() ?: return
        if (renderer.editor === editor) {
            rejectSuggestion(DismissReason.SELECTION_CHANGED)
        }
    }

    fun showSuggestion(editor: Editor, anchorOffset: Int, insertionOffset: Int, text: String) {
        ApplicationManager.getApplication().runReadAction {
            if (!shouldRequestCompletionUnsafe(editor, anchorOffset)) return@runReadAction
            if (insertionOffset !in 0..editor.document.textLength) return@runReadAction
            val key = RejectionCache.SuggestionKey(
                editor.document.text.hashCode(),
                anchorOffset,
                31 * insertionOffset + text.hashCode(),
            )
            if (!rejectionCache.shouldShow(key)) return@runReadAction
            if (text.isBlank()) return@runReadAction

            val suffix = editor.document.text.substring(insertionOffset)
            if (suffix.startsWith(text)) return@runReadAction

            clearSuggestion()
            val renderer = GhostTextRenderer(editor, anchorOffset, insertionOffset, text)
            currentRenderer.set(renderer)
            renderer.show()
        }
    }

    private fun ensureProviders() {
        val settings = PluginSettings.getInstance().state
        if (settings.autocompleteProvider != currentProviderType) {
            fimProvider?.dispose()
            nextEditProvider?.dispose()
            fimProvider = AutocompleteProviderFactory.createFimProvider(settings)
            nextEditProvider = AutocompleteProviderFactory.createNextEditProvider(settings)
            currentProviderType = settings.autocompleteProvider
        }
    }

    private fun clearSuggestion() {
        currentRenderer.getAndSet(null)?.dispose()
    }

    private fun buildRequest(editor: Editor, offset: Int): AutocompleteRequest? {
        val documentText = editor.document.text
        if (offset < 0 || offset > documentText.length) return null
        if (!shouldRequestCompletion(editor, offset)) return null

        val prefix = documentText.substring(0, offset)
        val suffix = documentText.substring(offset)
        if (prefix.isBlank() && suffix.isBlank()) return null
        if (documentText.length > MAX_DOCUMENT_CHARS) return null
        if (prefix.takeLast(LOOKBACK_FOR_NON_WHITESPACE).isBlank() && suffix.take(LOOKBACK_FOR_NON_WHITESPACE).isBlank()) {
            return null
        }

        val request = AutocompleteRequest(
            prefix = prefix,
            suffix = suffix,
            filePath = editor.virtualFile?.path,
            language = editor.virtualFile?.extension,
        )

        // For Inception Labs, enrich with context tracker data for Next Edit
        val settings = PluginSettings.getInstance().state
        if (settings.autocompleteProvider == AutocompleteProviderType.INCEPTION_LABS) {
            val tracker = project.service<EditContextTracker>()
            return request.copy(
                cursorOffset = offset,
                recentlyViewedSnippets = tracker.recentSnippets,
                editDiffHistory = tracker.recentDiffs,
            )
        }

        return request
    }

    private fun shouldRequestCompletion(editor: Editor, offset: Int): Boolean =
        ApplicationManager.getApplication().runReadAction<Boolean> {
            if (project.isDisposed || editor.isDisposed || editor.isViewer) return@runReadAction false
            if (editor.selectionModel.hasSelection()) return@runReadAction false
            if (offset != editor.caretModel.offset) return@runReadAction false
            offset in 0..editor.document.textLength
        }

    private fun shouldShowForCurrentState(editor: Editor, offset: Int, documentStamp: Long, requestId: Long): Boolean =
        ApplicationManager.getApplication().runReadAction<Boolean> {
            if (!shouldRequestCompletionUnsafe(editor, offset)) return@runReadAction false
            if (editor.document.modificationStamp != documentStamp) return@runReadAction false
            if (requestSequence.get() != requestId) return@runReadAction false
            true
        }

    private fun shouldRequestCompletionUnsafe(editor: Editor, offset: Int): Boolean {
        if (project.isDisposed || editor.isDisposed || editor.isViewer) return false
        if (editor.selectionModel.hasSelection()) return false
        if (offset != editor.caretModel.offset) return false
        return offset in 0..editor.document.textLength
    }

    override fun dispose() {
        pendingJob?.cancel()
        scope.cancel()
        fimProvider?.dispose()
        nextEditProvider?.dispose()
        fimProvider = null
        nextEditProvider = null
        clearSuggestion()
    }

    enum class DismissReason {
        ESCAPE,
        CARET_MOVED,
        FOCUS_LOST,
        TYPING,
        SELECTION_CHANGED,
    }

    companion object {
        private const val MAX_DOCUMENT_CHARS = 250_000
        private const val MAX_SUGGESTION_CHARS = 400
        private const val LOOKBACK_FOR_NON_WHITESPACE = 80
    }
}
