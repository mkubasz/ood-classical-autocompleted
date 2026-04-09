package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

internal object InlineCorrectnessFilter {

    sealed interface Result {
        data object Pass : Result
        data object Fail : Result
        data object Timeout : Result
    }

    fun check(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot?,
        timeLimitMs: Long = DEFAULT_TIME_LIMIT_MS,
    ): Result {
        val project = snapshot?.project ?: return Result.Pass
        if (project.isDisposed) return Result.Pass

        val insertionOffset = candidate.insertionOffset
        val documentText = snapshot.documentText
        if (insertionOffset !in 0..documentText.length) return Result.Pass

        val modifiedText = documentText.substring(0, insertionOffset) +
            candidate.text +
            documentText.substring(insertionOffset)

        val insertedRange = insertionOffset until (insertionOffset + candidate.text.length)

        return try {
            val startTime = System.nanoTime()
            ApplicationManager.getApplication().runReadAction<Result> {
                val psiFile = TemporaryPsiFileSupport.createTemporaryPsiFile(
                    project = project,
                    text = modifiedText,
                    filePath = snapshot.filePath,
                ) ?: return@runReadAction Result.Pass

                var syntaxErrors = 0
                var unresolvedRefs = 0
                var timedOut = false

                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        val elapsed = (System.nanoTime() - startTime) / 1_000_000
                        if (elapsed > timeLimitMs) {
                            timedOut = true
                            stopWalking()
                            return
                        }

                        val range = element.textRange ?: run { super.visitElement(element); return }
                        val inInsertedRegion = range.startOffset < insertedRange.last + 1 &&
                            range.endOffset > insertedRange.first
                        if (!inInsertedRegion) {
                            super.visitElement(element)
                            return
                        }

                        if (element is PsiErrorElement) {
                            syntaxErrors++
                        }

                        if (element is PsiNamedElement || isIdentifierLeaf(element)) {
                            element.references.forEach { ref ->
                                if (runCatching { ref.resolve() }.getOrNull() == null) {
                                    unresolvedRefs++
                                }
                            }
                        }

                        super.visitElement(element)
                    }
                })

                if (timedOut) return@runReadAction Result.Timeout
                if (syntaxErrors > MAX_TOLERATED_SYNTAX_ERRORS) return@runReadAction Result.Fail
                if (unresolvedRefs > MAX_TOLERATED_UNRESOLVED) return@runReadAction Result.Fail
                Result.Pass
            }
        } catch (_: Exception) {
            Result.Pass
        }
    }

    private fun isIdentifierLeaf(element: PsiElement): Boolean {
        if (element.firstChild != null) return false
        val text = element.text
        return text.isNotEmpty() && (text[0].isLetter() || text[0] == '_') && text.length >= 2
    }

    private const val DEFAULT_TIME_LIMIT_MS = 50L
    private const val MAX_TOLERATED_SYNTAX_ERRORS = 2
    private const val MAX_TOLERATED_UNRESOLVED = 3
}
