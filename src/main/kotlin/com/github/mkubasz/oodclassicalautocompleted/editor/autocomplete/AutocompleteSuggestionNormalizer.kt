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
        suggestion = stripLeadingSuffixDuplication(suggestion, request.suffix)
        suggestion = stripDuplicateClosingBracket(request.prefix, suggestion, request.suffix)

        val result = suggestion.trimEnd().take(maxChars)
        return if (result.length < MIN_DISPLAY_LENGTH) "" else result
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
        val exactOverlap = longestOverlap(
            suffixSource = suggestion.takeLast(MAX_OVERLAP_WINDOW),
            prefixSource = suffix.take(MAX_OVERLAP_WINDOW),
        )
        if (exactOverlap > 0) return suggestion.dropLast(exactOverlap)

        val normalizedOverlap = longestNormalizedOverlap(
            suggestionTail = suggestion.takeLast(MAX_OVERLAP_WINDOW),
            suffixHead = suffix.take(MAX_OVERLAP_WINDOW),
        )
        return if (normalizedOverlap > 0) suggestion.dropLast(normalizedOverlap) else suggestion
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

    private fun longestNormalizedOverlap(suggestionTail: String, suffixHead: String): Int {
        val normalizedSuffix = suffixHead.trimStart()
        if (normalizedSuffix.isEmpty()) return 0

        val maxLength = minOf(suggestionTail.length, normalizedSuffix.length)
        for (length in maxLength downTo 1) {
            if (suggestionTail.takeLast(length).trimEnd() == normalizedSuffix.take(length).trimEnd()) {
                return length
            }
        }
        return 0
    }

    private fun stripLeadingSuffixDuplication(suggestion: String, suffix: String): String {
        if (suggestion.isEmpty() || suffix.isEmpty()) return suggestion
        val suggestionFirstLine = suggestion.substringBefore('\n')
        val suffixFirstLine = suffix.substringBefore('\n')
        if (suggestionFirstLine.isEmpty() || suffixFirstLine.isEmpty()) return suggestion

        val maxCheck = minOf(suggestionFirstLine.length, suffixFirstLine.length, MAX_OVERLAP_WINDOW)
        for (length in maxCheck downTo 1) {
            if (suggestionFirstLine.take(length) == suffixFirstLine.take(length)) {
                return suggestion.drop(length)
            }
        }
        return suggestion
    }

    private fun stripDuplicateClosingBracket(prefix: String, suggestion: String, suffix: String): String {
        if (suggestion.isEmpty() || suffix.isEmpty()) return suggestion
        val suffixFirst = suffix.trimStart().firstOrNull() ?: return suggestion
        if (suffixFirst !in CLOSING_BRACKETS) return suggestion

        val opener = BRACKET_PAIRS[suffixFirst] ?: return suggestion
        val prefixBalance = prefix.count { it == opener } - prefix.count { it == suffixFirst }
        if (prefixBalance <= 0) return suggestion

        val firstClose = suggestion.indexOf(suffixFirst)
        if (firstClose < 0) return suggestion

        val suggestionBalanceBefore = suggestion.substring(0, firstClose).let { before ->
            before.count { it == opener } - before.count { it == suffixFirst }
        }
        if (prefixBalance + suggestionBalanceBefore <= 0) return suggestion

        return suggestion.removeRange(firstClose, firstClose + 1)
    }

    private val CLOSING_BRACKETS = setOf(')', ']', '}')
    private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
    private const val MIN_DISPLAY_LENGTH = 2
    private const val MAX_OVERLAP_WINDOW = 200
}
