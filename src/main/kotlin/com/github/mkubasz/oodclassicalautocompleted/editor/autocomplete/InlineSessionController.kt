package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEventListener
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

internal class InlineSessionController(
    private val project: Project,
    private val scope: CoroutineScope,
    private val sessions: SuggestionSessionStore,
    private val metric: (AutocompleteMetricType, Array<out Pair<String, Any?>>) -> Unit,
    private val recordAcceptedSuggestion: (Editor, AcceptedSuggestionKind, Int, String) -> Unit,
) {
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
        synchronized(sessions.supportedEditors) {
            if (!sessions.supportedEditors.add(editor)) return
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
        synchronized(sessions.supportedEditors) {
            sessions.supportedEditors.remove(editor)
        }
        sessions.pendingSuggestions.remove(editor)
        sessions.acceptedSuggestionTrackers.remove(editor)?.clear()
        clearInlineState(editor)
        InlineCompletion.remove(editor)
    }

    fun hasVisibleInline(editor: Editor): Boolean =
        sessions.inlineStates[editor]?.visible == true

    fun hasAlternativeSuggestions(editor: Editor): Boolean =
        (sessions.inlineStates[editor]?.candidates?.size ?: 0) > 1

    fun takePendingSuggestion(editor: Editor): InlineCompletionCandidate? =
        sessions.pendingSuggestions.remove(editor)

    fun storeInlineState(editor: Editor, requestId: Long, candidates: List<InlineCompletionCandidate>) {
        val primary = candidates.firstOrNull() ?: return
        val documentHash = editor.document.text.hashCode()
        sessions.inlineStates[editor] = InlineSessionState(
            requestId = requestId,
            candidates = candidates,
            rejectionKey = suggestionKey(documentHash, primary.insertionOffset, primary.text),
        )
    }

    fun clearInlineState(editor: Editor) {
        sessions.inlineStates.remove(editor)
    }

    fun acceptInline(editor: Editor): Boolean {
        val state = sessions.inlineStates[editor] ?: return false
        if (!state.visible) return false
        InlineCompletion.getHandlerOrNull(editor)?.insert()
        activeCandidate(state)?.let { candidate ->
            recordAcceptedSuggestion(
                editor,
                AcceptedSuggestionKind.INLINE,
                candidate.insertionOffset,
                candidate.text,
            )
        }
        metric(AutocompleteMetricType.INLINE_ACCEPTED_INLINE_ONLY, arrayOf("offset" to editor.caretModel.offset))
        return true
    }

    fun acceptOnTab(editor: Editor): Boolean {
        val state = sessions.inlineStates[editor] ?: return false
        if (!state.visible) return false
        InlineCompletion.getHandlerOrNull(editor)?.insert()
        activeCandidate(state)?.let { candidate ->
            recordAcceptedSuggestion(
                editor,
                AcceptedSuggestionKind.INLINE,
                candidate.insertionOffset,
                candidate.text,
            )
        }
        metric(AutocompleteMetricType.INLINE_ACCEPTED, arrayOf("offset" to editor.caretModel.offset))
        return true
    }

    fun acceptNextWord(editor: Editor): Boolean {
        val state = sessions.inlineStates[editor] ?: return false
        if (!state.visible) return false
        val candidate = state.candidates.firstOrNull() ?: return false
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
        recordAcceptedSuggestion(editor, AcceptedSuggestionKind.INLINE_WORD, candidate.insertionOffset, acceptedPart)
        metric(AutocompleteMetricType.INLINE_ACCEPT_WORD, arrayOf("chars" to acceptedPart.length))

        if (remainder.isNotBlank()) {
            sessions.pendingSuggestions[editor] = InlineCompletionCandidate(
                text = remainder.trimStart(),
                insertionOffset = candidate.insertionOffset + acceptedPart.length,
            )
        }
        return true
    }

    fun acceptNextLine(editor: Editor): Boolean {
        val state = sessions.inlineStates[editor] ?: return false
        if (!state.visible) return false
        val candidate = state.candidates.firstOrNull() ?: return false
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
        recordAcceptedSuggestion(editor, AcceptedSuggestionKind.INLINE_LINE, candidate.insertionOffset, acceptedPart)
        metric(AutocompleteMetricType.INLINE_ACCEPT_LINE, arrayOf("chars" to acceptedPart.length))

        if (remainder.isNotBlank()) {
            sessions.pendingSuggestions[editor] = InlineCompletionCandidate(
                text = remainder,
                insertionOffset = candidate.insertionOffset + acceptedPart.length,
            )
        }
        return true
    }

    fun cycle(editor: Editor, step: Int): Boolean {
        val state = sessions.inlineStates[editor] ?: return false
        val candidates = state.candidates
        if (candidates.size <= 1) return false

        val nextIndex = ((state.currentIndex + step) % candidates.size + candidates.size) % candidates.size
        sessions.inlineStates[editor] = state.copy(currentIndex = nextIndex)
        sessions.pendingSuggestions[editor] = candidates[nextIndex]
        InlineCompletion.getHandlerOrNull(editor)?.cancel()
        metric(AutocompleteMetricType.INLINE_CYCLE, arrayOf("index" to nextIndex, "total" to candidates.size))
        return true
    }

    fun rejectInline(editor: Editor, dismissReason: AutocompleteService.DismissReason): Boolean {
        sessions.pendingSuggestions.remove(editor)
        val state = sessions.inlineStates.remove(editor) ?: return false
        state.rejectionKey?.let {
            sessions.rejectionCache.recordRejection(
                key = it,
                reason = dismissReason,
                shownAt = state.shownAt,
            )
        }
        InlineCompletion.getHandlerOrNull(editor)?.cancel()
        metric(AutocompleteMetricType.INLINE_REJECTED, arrayOf("reason" to dismissReason))
        return true
    }

    fun onFocusLost(editor: Editor) {
        sessions.pendingSuggestions.remove(editor)
    }

    fun testingSeedInlineState(
        editor: Editor,
        candidates: List<InlineCompletionCandidate>,
        requestId: Long = 1L,
        visible: Boolean = true,
        shownAt: Long = 0L,
        currentIndex: Int = 0,
    ) {
        val primary = candidates.firstOrNull()
        sessions.inlineStates[editor] = InlineSessionState(
            requestId = requestId,
            candidates = candidates,
            rejectionKey = primary?.let {
                suggestionKey(
                    documentHash = editor.document.text.hashCode(),
                    insertionOffset = it.insertionOffset,
                    text = it.text,
                )
            },
            visible = visible,
            shownAt = shownAt,
            currentIndex = currentIndex,
        )
    }

    fun testingInlineState(editor: Editor): AutocompleteService.TestingInlineState? =
        sessions.inlineStates[editor]?.let { state ->
            AutocompleteService.TestingInlineState(
                visible = state.visible,
                currentIndex = state.currentIndex,
                candidates = state.candidates,
            )
        }

    private fun onInlineShown(editor: Editor) {
        val current = sessions.inlineStates[editor] ?: return
        if (current.visible) return
        sessions.inlineStates[editor] = current.copy(
            visible = true,
            shownAt = System.currentTimeMillis(),
        )
        metric(AutocompleteMetricType.INLINE_SHOWN, arrayOf("count" to current.candidates.size))
    }

    private fun activeCandidate(state: InlineSessionState): InlineCompletionCandidate? =
        state.candidates.getOrNull(state.currentIndex) ?: state.candidates.firstOrNull()

    private fun suggestionKey(
        documentHash: Int,
        insertionOffset: Int,
        text: String,
    ): RejectionCache.SuggestionKey = RejectionCache.SuggestionKey(
        documentHash = documentHash,
        offset = insertionOffset,
        suggestionHash = text.hashCode(),
    )

    private fun findNextWordBoundary(text: String): Int {
        if (text.isEmpty()) return 0
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        if (i == 0) i = 1
        return i
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
}
