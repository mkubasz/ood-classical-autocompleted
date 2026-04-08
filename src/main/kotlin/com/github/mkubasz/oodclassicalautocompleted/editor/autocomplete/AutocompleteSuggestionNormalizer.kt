package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest

internal object AutocompleteSuggestionNormalizer {

    fun normalize(rawText: String, request: AutocompleteRequest, maxChars: Int): String {
        val normalizedLineEndings = rawText.replace("\r", "")
        if (normalizedLineEndings.trim() == "(no output)") return ""

        var suggestion = unwrapCodeFence(normalizedLineEndings).trimEnd()
        if (suggestion.isBlank()) return ""

        suggestion = stripPrefixEcho(request.prefix, suggestion)
        suggestion = stripSuffixOverlap(suggestion, request.suffix)

        return suggestion.trimEnd().take(maxChars)
    }

    private fun unwrapCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val firstNewline = trimmed.indexOf('\n')
        val lastFence = trimmed.lastIndexOf("```")
        if (firstNewline < 0 || lastFence <= firstNewline) return ""

        return trimmed.substring(firstNewline + 1, lastFence)
    }

    private fun stripPrefixEcho(prefix: String, suggestion: String): String {
        val overlap = longestOverlap(
            suffixSource = prefix.takeLast(MAX_OVERLAP_WINDOW),
            prefixSource = suggestion.take(MAX_OVERLAP_WINDOW),
        )

        return suggestion.drop(overlap)
    }

    private fun stripSuffixOverlap(suggestion: String, suffix: String): String {
        val overlap = longestOverlap(
            suffixSource = suggestion.takeLast(MAX_OVERLAP_WINDOW),
            prefixSource = suffix.take(MAX_OVERLAP_WINDOW),
        )

        return if (overlap == 0) suggestion else suggestion.dropLast(overlap)
    }

    private fun longestOverlap(suffixSource: String, prefixSource: String): Int {
        val maxLength = minOf(suffixSource.length, prefixSource.length)

        for (length in maxLength downTo 1) {
            if (suffixSource.takeLast(length) == prefixSource.take(length)) {
                return length
            }
        }

        return 0
    }

    private const val MAX_OVERLAP_WINDOW = 200
}
