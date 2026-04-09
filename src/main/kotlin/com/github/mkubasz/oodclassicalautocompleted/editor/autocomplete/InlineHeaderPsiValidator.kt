package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

internal object InlineHeaderPsiValidator {

    private data class ValidationSource(
        val text: String,
        val relevantRange: IntRange,
    )

    sealed interface Result {
        data object Valid : Result

        data class Retryable(
            val errorDescription: String,
            val expectedContinuation: String? = null,
        ) : Result

        data object Invalid : Result
    }

    fun validate(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot?,
    ): Result {
        if (!shouldValidate(request, snapshot)) return Result.Valid
        if (request.inlineContext?.headerValidationRetry == true) {
            return if (hasRelevantError(candidate, request, snapshot!!)) Result.Invalid else Result.Valid
        }

        val error = findRelevantError(candidate, request, snapshot!!) ?: return Result.Valid
        return Result.Retryable(
            errorDescription = sanitizeErrorDescription(error.errorDescription),
            expectedContinuation = expectedContinuation(request),
        )
    }

    private fun shouldValidate(
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot?,
    ): Boolean {
        val language = request.language.orEmpty().lowercase()
        val context = request.inlineContext
        if (snapshot?.project == null || snapshot.project.isDisposed) return false
        if (!language.contains("py") && !language.contains("python")) return false
        return context?.isDefinitionHeaderLikeContext == true ||
            context?.isClassBaseListLikeContext == true
    }

    private fun hasRelevantError(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot,
    ): Boolean = findRelevantError(candidate, request, snapshot) != null

    private fun findRelevantError(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot,
    ): PsiErrorElement? = runCatching {
        ApplicationManager.getApplication().runReadAction<PsiErrorElement?> {
            val validationSource = buildValidationSource(candidate, request) ?: return@runReadAction null
            val psiFile = TemporaryPsiFileSupport.createTemporaryPsiFile(
                project = snapshot.project ?: return@runReadAction null,
                text = validationSource.text,
                filePath = snapshot.filePath,
            ) ?: return@runReadAction null

            var error: PsiErrorElement? = null
            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitErrorElement(element: PsiErrorElement) {
                    if (error != null) return
                    if (!isRelevantError(element, validationSource.relevantRange)) return
                    error = element
                }
            })
            error
        }
    }.getOrNull()

    private fun buildValidationSource(
        candidate: InlineCompletionCandidate,
        request: AutocompleteRequest,
    ): ValidationSource? {
        val currentLinePrefix = request.prefix.substringAfterLast('\n')
        val currentLineSuffix = request.suffix.substringBefore('\n')
        val headerLine = currentLinePrefix + candidate.text + currentLineSuffix

        val decoratorLines = decoratorContextLines(request.prefix)
        val fragmentLines = buildList {
            addAll(decoratorLines)
            add(headerLine)
        }

        val fragment = fragmentLines.joinToString("\n").trimEnd()
        if (fragment.isBlank()) return null

        val headerStartOffset = decoratorLines.sumOf(String::length) +
            if (decoratorLines.isEmpty()) 0 else decoratorLines.size

        val validationText = if (needsSyntheticBody(fragment, request)) {
            "$fragment\n${syntheticIndent(currentLinePrefix)}pass"
        } else {
            fragment
        }

        return ValidationSource(
            text = validationText,
            relevantRange = headerStartOffset until (headerStartOffset + headerLine.length),
        )
    }

    private fun sanitizeErrorDescription(description: String): String =
        description.replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun decoratorContextLines(prefix: String): List<String> {
        val lines = prefix.replace("\r", "").lines()
        val currentLineIndex = lines.lastIndex
        if (currentLineIndex <= 0) return emptyList()

        val decorators = ArrayDeque<String>()
        var index = currentLineIndex - 1
        while (index >= 0) {
            val line = lines[index].trimEnd()
            if (line.trimStart().startsWith("@")) {
                decorators.addFirst(line)
                index--
                continue
            }
            if (line.isBlank()) {
                index--
                continue
            }
            break
        }
        return decorators.toList()
    }

    private fun needsSyntheticBody(fragment: String, request: AutocompleteRequest): Boolean {
        val context = request.inlineContext ?: return false
        if (!context.isDefinitionHeaderLikeContext && !context.isClassBaseListLikeContext) return false

        val headerLine = fragment.substringAfterLast('\n')
        val content = headerLine.substringBefore('#').trimEnd()
        return content.endsWith(':')
    }

    private fun syntheticIndent(currentLinePrefix: String): String {
        val baseIndent = currentLinePrefix.takeWhile { it == ' ' || it == '\t' }
        return when {
            '\t' in baseIndent -> baseIndent + "\t"
            else -> baseIndent + "    "
        }
    }

    private fun expectedContinuation(request: AutocompleteRequest): String? {
        val context = request.inlineContext ?: return null
        val prefix = context.classBaseReferencePrefix ?: return null
        val matchingType = context.matchingTypeNames.singleOrNull {
            it.startsWith(prefix, ignoreCase = true)
        } ?: return null

        return matchingType.drop(prefix.length) + "):"
    }

    private fun isRelevantError(element: PsiErrorElement, relevantRange: IntRange): Boolean {
        val errorRange = element.textRange ?: return false
        val relevantEndExclusive = relevantRange.last + 1
        return errorRange.startOffset < relevantEndExclusive && errorRange.endOffset > relevantRange.first
    }
}
