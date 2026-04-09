package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineMode
import com.github.mkubasz.oodclassicalautocompleted.completion.pipeline.CompletionEngine
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.ModelProviderCoordinator
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.ProviderRegistry
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NextEditController(
    private val project: Project,
    private val scope: CoroutineScope,
    private val sessions: SuggestionSessionStore,
    private val providerRegistry: ProviderRegistry,
    private val completionEngine: CompletionEngine,
    private val metric: (AutocompleteMetricType, Array<out Pair<String, Any?>>) -> Unit,
    private val onProviderFailure: (String, Exception) -> Unit,
    private val recordAcceptedSuggestion: (Editor, AcceptedSuggestionKind, Int, String) -> Unit,
) {
    fun hasActiveSuggestion(editor: Editor): Boolean =
        sessions.nextEditStates[editor]?.candidate != null || sessions.nextEditStates[editor]?.preview != null

    fun canAcceptOnTab(editor: Editor): Boolean =
        sessions.nextEditStates[editor]?.preview != null || sessions.nextEditStates[editor]?.candidate != null

    fun acceptOnTab(editor: Editor): Boolean {
        val state = sessions.nextEditStates[editor] ?: return false
        if (state.preview != null) {
            val postApply = suppressCaretHandling(editor) {
                state.preview.apply(project)
            }
            recordAcceptedSuggestion(
                editor,
                AcceptedSuggestionKind.NEXT_EDIT,
                state.candidate.startOffset,
                state.candidate.replacementText,
            )
            metric(
                AutocompleteMetricType.NEXT_EDIT_ACCEPTED,
                arrayOf(
                    "start" to state.candidate.startOffset,
                    "imports_resolved" to postApply.importsResolved,
                    "class_references_shortened" to postApply.classReferencesShortened,
                    "reformatted" to postApply.reformatted,
                ),
            )
            clearNextEdit(editor)
            return true
        }

        val preview = NextEditPreview(
            editor = editor,
            candidate = state.candidate,
            maxPreviewLines = PluginSettings.getInstance().state.nextEditPreviewMaxLines,
        )
        suppressCaretHandling(editor) {
            preview.jumpToPreview()
            preview.show()
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
        sessions.nextEditStates[editor] = state.copy(preview = preview)
        metric(
            AutocompleteMetricType.NEXT_EDIT_PREVIEWED,
            arrayOf("start" to state.candidate.startOffset, "end" to state.candidate.endOffset),
        )
        return true
    }

    fun reject(editor: Editor, dismissReason: AutocompleteService.DismissReason): Boolean {
        cancelNextEditJob(editor)
        if (!clearNextEdit(editor)) return false
        metric(AutocompleteMetricType.NEXT_EDIT_REJECTED, arrayOf("reason" to dismissReason))
        return true
    }

    fun onCaretMoved(editor: Editor) {
        synchronized(sessions.caretSuppression) {
            if (sessions.caretSuppression.remove(editor)) return
        }
        cancelNextEditJob(editor)
        clearNextEdit(editor)
    }

    fun onFocusLost(editor: Editor) {
        cancelNextEditJob(editor)
        clearNextEdit(editor)
    }

    fun onSelectionChanged(editor: Editor) {
        cancelNextEditJob(editor)
        clearNextEdit(editor)
    }

    fun onDocumentChanged(
        editor: Editor,
        changeOffset: Int,
        oldLength: Int,
        newText: String,
    ) {
        val nextEdit = sessions.nextEditStates[editor] ?: return
        if (nextEdit.preview != null) {
            clearNextEdit(editor)
            return
        }

        val editEnd = changeOffset + oldLength
        val candidate = nextEdit.candidate
        if (changeOffset < candidate.endOffset && editEnd > candidate.startOffset) {
            clearNextEdit(editor)
        } else if (editEnd <= candidate.startOffset) {
            val delta = newText.length - oldLength
            if (delta != 0) {
                sessions.nextEditStates[editor] = nextEdit.copy(
                    candidate = candidate.copy(
                        startOffset = candidate.startOffset + delta,
                        endOffset = candidate.endOffset + delta,
                    )
                )
            }
        }
    }

    fun requestCompletion(editor: Editor, preserveCurrentSuggestion: Boolean = false) {
        if (!preserveCurrentSuggestion) {
            cancelNextEditJob(editor)
            clearNextEdit(editor)
        }
    }

    fun unregisterEditor(editor: Editor) {
        cancelNextEditJob(editor)
        clearNextEdit(editor)
    }

    fun launchNextEditFetch(
        editor: Editor,
        bundle: CompletionEngine.PipelineBundle,
        requestId: Long,
    ) {
        if (!PluginSettings.getInstance().state.nextEditEnabled) {
            clearNextEdit(editor)
            return
        }

        val provider = providerRegistry.nextEditProvider() ?: return
        cancelNextEditJob(editor)
        val job = scope.launch {
            val pipelineRequest = completionEngine.buildRequest(bundle, PipelineMode.NEXT_EDIT)
            val artifact = try {
                ModelProviderCoordinator.completeNextEdit(
                    call = ModelCall(providerId = provider.id, request = pipelineRequest),
                    provider = provider,
                    onFailure = onProviderFailure,
                )?.let { completionEngine.applyPostProcessors(pipelineRequest, it) }
            } catch (e: CancellationException) {
                throw e
            } ?: return@launch

            val snapshot = completionEngine.toCompletionContextSnapshot(bundle)
            val candidate = artifact.nextEditCandidates.firstNotNullOfOrNull {
                nextEditCandidateOrNull(it, snapshot)
            }

            withContext(Dispatchers.Main) {
                if (editor.isDisposed) return@withContext
                if (sessions.latestRequestIds[editor] != requestId) return@withContext

                if (candidate == null) {
                    clearNextEdit(editor)
                    return@withContext
                }

                sessions.nextEditStates[editor] = NextEditSessionState(
                    requestId = requestId,
                    candidate = candidate,
                )
                metric(
                    AutocompleteMetricType.NEXT_EDIT_READY,
                    arrayOf("start" to candidate.startOffset, "end" to candidate.endOffset),
                )
            }
        }
        sessions.nextEditJobs[editor] = job
    }

    fun testingSeedNextEditState(
        editor: Editor,
        candidate: NextEditCompletionCandidate,
        requestId: Long = 1L,
    ) {
        sessions.nextEditStates[editor] = NextEditSessionState(
            requestId = requestId,
            candidate = candidate,
        )
    }

    fun testingHasNextEditPreview(editor: Editor): Boolean =
        sessions.nextEditStates[editor]?.preview != null

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
        val removed = sessions.nextEditStates.remove(editor) ?: return false
        removed.preview?.dispose()
        return true
    }

    private fun cancelNextEditJob(editor: Editor) {
        sessions.nextEditJobs.remove(editor)?.cancel()
    }

    private fun <T> suppressCaretHandling(editor: Editor, block: () -> T): T {
        synchronized(sessions.caretSuppression) {
            sessions.caretSuppression.add(editor)
        }
        try {
            return block()
        } finally {
            synchronized(sessions.caretSuppression) {
                sessions.caretSuppression.remove(editor)
            }
        }
    }

    companion object {
        private const val MAX_NEXT_EDIT_CHARS = 800
    }
}
