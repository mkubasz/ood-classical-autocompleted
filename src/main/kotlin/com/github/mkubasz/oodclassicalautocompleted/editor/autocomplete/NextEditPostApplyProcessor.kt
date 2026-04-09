package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.IncorrectOperationException

internal data class NextEditPostApplyResult(
    val importsResolved: Boolean = false,
    val classReferencesShortened: Boolean = false,
    val reformatted: Boolean = false,
)

internal object NextEditPostApplyProcessor {
    private val log = logger<NextEditPostApplyProcessor>()

    fun applyBestEffort(
        project: Project,
        editor: Editor,
        changedRange: TextRange,
    ): NextEditPostApplyResult {
        val safeRange = TextRange(
            changedRange.startOffset.coerceIn(0, editor.document.textLength),
            changedRange.endOffset.coerceIn(
                changedRange.startOffset.coerceIn(0, editor.document.textLength),
                editor.document.textLength,
            ),
        )
        if (safeRange.isEmpty) return NextEditPostApplyResult()

        val marker = editor.document.createRangeMarker(safeRange.startOffset, safeRange.endOffset).apply {
            isGreedyToLeft = true
            isGreedyToRight = true
        }
        return try {
            commitDocument(project, editor)
            val shortened = shortenJavaClassReferences(project, editor, marker)
            commitDocument(project, editor)
            val importsResolved = currentRange(marker)?.let {
                NextEditImportActionResolver.applyBestEffort(project, editor, it)
            } ?: false
            commitDocument(project, editor)
            val reformatted = reformatChangedRange(project, editor, marker)
            commitDocument(project, editor)
            NextEditPostApplyResult(
                importsResolved = importsResolved,
                classReferencesShortened = shortened,
                reformatted = reformatted,
            )
        } finally {
            marker.dispose()
        }
    }

    private fun shortenJavaClassReferences(
        project: Project,
        editor: Editor,
        marker: RangeMarker,
    ): Boolean {
        val psiFile = committedPsiFile(project, editor) as? PsiJavaFile ?: return false
        val range = currentRange(marker) ?: return false
        val originalText = editor.document.getText(range)
        val qualifiedNames = FULLY_QUALIFIED_CLASS.findAll(originalText)
            .map { it.value }
            .distinct()
            .toList()
        if (qualifiedNames.isEmpty()) return false

        val importsToAdd = qualifiedNames
            .filterNot { it.startsWith("java.lang.") }
            .filterNot { qualifiedName -> hasJavaImport(psiFile, qualifiedName) }
            .sorted()

        val importsAdded = if (importsToAdd.isEmpty()) {
            false
        } else {
            runMutation(project, editor) {
                insertJavaImports(editor, psiFile, importsToAdd)
            }
        }

        commitDocument(project, editor)

        val textShortened = runMutation(project, editor) {
            val updatedRange = currentRange(marker) ?: return@runMutation
            var updatedText = editor.document.getText(updatedRange)
            qualifiedNames.forEach { qualifiedName ->
                updatedText = updatedText.replace(qualifiedName, qualifiedName.substringAfterLast('.'))
            }
            val replacementRange = currentRange(marker) ?: return@runMutation
            editor.document.replaceString(
                replacementRange.startOffset,
                replacementRange.endOffset,
                updatedText,
            )
        }
        return importsAdded || textShortened
    }

    private fun reformatChangedRange(
        project: Project,
        editor: Editor,
        marker: RangeMarker,
    ): Boolean {
        val psiFile = committedPsiFile(project, editor) ?: return false
        val range = expandToLineBoundaries(editor, currentRange(marker) ?: return false)
        return runMutation(project, editor) {
            CodeStyleManager.getInstance(project).reformatText(
                psiFile,
                range.startOffset,
                range.endOffset,
            )
        }
    }

    private fun runMutation(
        project: Project,
        editor: Editor,
        mutation: () -> Unit,
    ): Boolean {
        val before = editor.document.text
        val success = runCatching {
            WriteCommandAction.runWriteCommandAction(project) {
                mutation()
            }
            true
        }.onFailure { error ->
            if (error !is IncorrectOperationException) {
                log.warn("Next-edit post-apply action failed", error)
            }
        }.getOrDefault(false)
        if (!success) return false
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        return before != editor.document.text
    }

    private fun committedPsiFile(project: Project, editor: Editor): PsiFile? =
        commitDocument(project, editor).getPsiFile(editor.document)

    private fun commitDocument(project: Project, editor: Editor): PsiDocumentManager =
        PsiDocumentManager.getInstance(project).also { manager ->
            manager.commitDocument(editor.document)
        }

    private fun currentRange(marker: RangeMarker): TextRange? {
        if (!marker.isValid) return null
        if (marker.startOffset >= marker.endOffset) return null
        return TextRange(marker.startOffset, marker.endOffset)
    }

    private fun expandToLineBoundaries(editor: Editor, range: TextRange): TextRange {
        val document = editor.document
        val startLine = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
        val endAnchor = (range.endOffset - 1).coerceIn(0, document.textLength.coerceAtLeast(1) - 1)
        val endLine = document.getLineNumber(endAnchor)
        val start = document.getLineStartOffset(startLine)
        val end = document.getLineEndOffset(endLine)
        return TextRange(start, end)
    }

    private fun hasJavaImport(psiFile: PsiJavaFile, qualifiedName: String): Boolean =
        psiFile.importList?.allImportStatements?.any { statement ->
            statement.text.contains(qualifiedName)
        } == true

    private fun insertJavaImports(
        editor: Editor,
        psiFile: PsiJavaFile,
        importsToAdd: List<String>,
    ) {
        if (importsToAdd.isEmpty()) return
        val document = editor.document
        val importBlock = importsToAdd.joinToString(separator = "\n", postfix = "\n") { "import $it;" }
        val importList = psiFile.importList
        val packageStatement = psiFile.packageStatement

        when {
            importList != null && importList.allImportStatements.isNotEmpty() -> {
                document.insertString(importList.textRange.endOffset, "\n$importBlock")
            }
            packageStatement != null -> {
                document.insertString(packageStatement.textRange.endOffset, "\n\n$importBlock")
            }
            else -> {
                document.insertString(0, "$importBlock\n")
            }
        }
    }

    private val FULLY_QUALIFIED_CLASS = Regex("""\b(?:[a-z_][A-Za-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*\b""")
}
