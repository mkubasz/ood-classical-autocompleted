package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.daemon.ReferenceImporter
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.util.function.BooleanSupplier

internal object NextEditImportActionResolver {

    fun applyBestEffort(
        project: Project,
        editor: Editor,
        changedRange: TextRange,
    ): Boolean {
        if (!PluginSettings.getInstance().state.nextEditResolveImports) return false
        val safeRange = TextRange(
            changedRange.startOffset.coerceIn(0, editor.document.textLength),
            changedRange.endOffset.coerceIn(changedRange.startOffset.coerceIn(0, editor.document.textLength), editor.document.textLength),
        )
        if (safeRange.isEmpty) return false

        if (tryReferenceImporters(project, editor, safeRange)) {
            return true
        }

        return tryImportQuickFix(project, editor, safeRange)
    }

    private fun tryReferenceImporters(
        project: Project,
        editor: Editor,
        changedRange: TextRange,
    ): Boolean {
        val offsets = identifierOffsets(editor.document.charsSequence, changedRange)
        if (offsets.isEmpty()) return false

        for (offset in offsets) {
            val psiFile = committedPsiFile(project, editor) ?: return false
            val importAction = runCatching {
                ApplicationManager.getApplication().executeOnPooledThread<BooleanSupplier?> {
                    ApplicationManager.getApplication().runReadAction<BooleanSupplier?> {
                        ReferenceImporter.EP_NAME.extensionList.firstNotNullOfOrNull { importer ->
                            importer.computeAutoImportAtOffset(editor, psiFile, offset, false)
                        }
                    }
                }
            }.getOrNull()?.get() ?: continue

            if (importAction.asBoolean) {
                commitDocument(project, editor)
                return true
            }
        }

        return false
    }

    private fun tryImportQuickFix(
        project: Project,
        editor: Editor,
        changedRange: TextRange,
    ): Boolean {
        val originalCaretOffset = editor.caretModel.offset
        val originalDocumentLength = editor.document.textLength
        val offsets = identifierOffsets(editor.document.charsSequence, changedRange)
        if (offsets.isEmpty()) return false

        for (offset in offsets) {
            val psiFile = committedPsiFile(project, editor) ?: return false
            editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))

            val importFixes = ShowIntentionActionsHandler
                .calcCachedIntentions(project, editor, psiFile)
                .allActions
                .asSequence()
                .map { cached ->
                    ImportFixCandidate(
                        action = cached.action,
                        displayName = cached.text,
                        range = cached.fixRange ?: TextRange(offset, offset + 1),
                    )
                }
                .filter { candidate -> rangesOverlap(candidate.range, changedRange) || candidate.range.containsOffset(offset) }
                .filter { candidate -> looksLikeImportAction(candidate.action, candidate.displayName) }
                .distinctBy { candidate -> candidate.displayName to candidate.range.startOffset }
                .toList()

            if (importFixes.size != 1) continue

            val importFix = importFixes.single()
            val applied = ShowIntentionActionsHandler.chooseActionAndInvoke(
                psiFile,
                editor,
                importFix.action,
                importFix.displayName,
                offset,
            )
            if (applied) {
                commitDocument(project, editor)
                restoreCaretNearOriginal(editor, originalCaretOffset, originalDocumentLength)
                return true
            }
        }

        editor.caretModel.moveToOffset(originalCaretOffset.coerceIn(0, editor.document.textLength))
        return false
    }

    private fun looksLikeImportAction(
        action: IntentionAction,
        displayName: String,
    ): Boolean {
        val text = listOf(displayName, action.text)
            .joinToString(" ")
            .lowercase()
        return "import" in text &&
            "optimize imports" !in text &&
            "unused import" !in text
    }

    private fun identifierOffsets(
        documentText: CharSequence,
        changedRange: TextRange,
    ): List<Int> {
        val safeStart = changedRange.startOffset.coerceIn(0, documentText.length)
        val safeEnd = changedRange.endOffset.coerceIn(safeStart, documentText.length)
        if (safeStart >= safeEnd) return emptyList()

        val text = documentText.subSequence(safeStart, safeEnd).toString()
        return IDENTIFIER_PATTERN.findAll(text)
            .map { match -> safeStart + match.range.first }
            .distinct()
            .take(MAX_IDENTIFIER_OFFSETS)
            .toList()
    }

    private fun committedPsiFile(project: Project, editor: Editor) =
        commitDocument(project, editor).let { PsiDocumentManager.getInstance(project).getPsiFile(editor.document) }

    private fun commitDocument(project: Project, editor: Editor): PsiDocumentManager =
        PsiDocumentManager.getInstance(project).also { manager ->
            manager.commitDocument(editor.document)
        }

    private fun rangesOverlap(first: TextRange, second: TextRange): Boolean =
        first.startOffset < second.endOffset && first.endOffset > second.startOffset

    private data class ImportFixCandidate(
        val action: IntentionAction,
        val displayName: String,
        val range: TextRange,
    )

    private fun restoreCaretNearOriginal(
        editor: Editor,
        originalCaretOffset: Int,
        originalDocumentLength: Int,
    ) {
        val lengthDelta = editor.document.textLength - originalDocumentLength
        val restoredOffset = (originalCaretOffset + lengthDelta).coerceIn(0, editor.document.textLength)
        editor.caretModel.moveToOffset(restoredOffset)
    }

    private val IDENTIFIER_PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
    private const val MAX_IDENTIFIER_OFFSETS = 32
}
