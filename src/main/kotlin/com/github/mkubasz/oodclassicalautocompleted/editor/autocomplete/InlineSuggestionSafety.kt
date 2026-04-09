package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate

internal object InlineSuggestionSafety {

    fun isSafe(candidate: InlineCompletionCandidate, request: ProviderRequest): Boolean {
        if ('\n' !in candidate.text) return true

        if (startsWithSafeBoundaryInsertedNewline(candidate, request)) {
            return true
        }

        val documentText = request.prefix + request.suffix
        val insertionOffset = candidate.insertionOffset.coerceIn(0, documentText.length)
        val lineStart = documentText.lastIndexOf('\n', (insertionOffset - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val lineEnd = documentText.indexOf('\n', insertionOffset)
            .let { if (it == -1) documentText.length else it }

        val linePrefix = documentText.substring(lineStart, insertionOffset)
        val lineSuffix = documentText.substring(insertionOffset, lineEnd)
        if (lineSuffix.isNotBlank()) return false

        val trimmedPrefix = linePrefix.trimEnd()
        if (trimmedPrefix.isEmpty()) return true

        return trimmedPrefix.last() in SAFE_MULTILINE_OPENERS
    }

    private fun startsWithSafeBoundaryInsertedNewline(
        candidate: InlineCompletionCandidate,
        request: ProviderRequest,
    ): Boolean {
        if (!candidate.text.startsWith('\n')) return false

        val withoutLeadingNewline = candidate.text.removePrefix("\n")
        return InlineSuggestionBoundaryAdjuster.needsLeadingNewline(
            text = withoutLeadingNewline,
            request = request,
            insertionOffset = candidate.insertionOffset,
        )
    }

    private val SAFE_MULTILINE_OPENERS = setOf(':', '{', '[')
}
