package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiErrorElement
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

                var errorCount = 0
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitErrorElement(element: PsiErrorElement) {
                        val elapsed = (System.nanoTime() - startTime) / 1_000_000
                        if (elapsed > timeLimitMs) {
                            stopWalking()
                            return
                        }
                        val range = element.textRange ?: return
                        if (range.startOffset < insertedRange.last + 1 && range.endOffset > insertedRange.first) {
                            errorCount++
                        }
                    }
                })

                val elapsed = (System.nanoTime() - startTime) / 1_000_000
                if (elapsed > timeLimitMs) return@runReadAction Result.Timeout

                if (errorCount > MAX_TOLERATED_ERRORS) Result.Fail else Result.Pass
            }
        } catch (_: Exception) {
            Result.Pass
        }
    }

    private const val DEFAULT_TIME_LIMIT_MS = 50L
    private const val MAX_TOLERATED_ERRORS = 2
}
