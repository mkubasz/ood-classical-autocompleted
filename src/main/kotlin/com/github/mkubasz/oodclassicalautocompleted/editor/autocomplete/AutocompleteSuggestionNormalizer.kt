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
        val prefixTail = prefix.takeLast(MAX_OVERLAP_WINDOW)
        val suggestionHead = suggestion.take(MAX_OVERLAP_WINDOW)

        val exact = longestOverlap(prefixTail, suggestionHead)
        if (exact > 0) return suggestion.drop(exact)

        val normalized = longestOverlapNormalized(prefixTail, suggestionHead)
        return if (normalized > 0) suggestion.drop(normalized) else suggestion
    }

    private fun stripSuffixOverlap(suggestion: String, suffix: String): String {
        val suggestionTail = suggestion.takeLast(MAX_OVERLAP_WINDOW)
        val suffixHead = suffix.take(MAX_OVERLAP_WINDOW)

        val exact = longestOverlap(suggestionTail, suffixHead)
        if (exact > 0) return suggestion.dropLast(exact)

        val normalized = longestOverlapNormalized(suggestionTail, suffixHead)
        return if (normalized > 0) suggestion.dropLast(normalized) else suggestion
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

    private fun longestOverlapNormalized(suffixSource: String, prefixSource: String): Int {
        val maxLength = minOf(suffixSource.length, prefixSource.length)
        for (length in maxLength downTo 1) {
            val sTail = normalizeForComparison(suffixSource.takeLast(length))
            val pHead = normalizeForComparison(prefixSource.take(length))
            if (sTail == pHead && sTail.isNotEmpty()) {
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
        for (length in maxCheck downTo 1) {
            val sNorm = normalizeForComparison(suggestionFirstLine.take(length))
            val xNorm = normalizeForComparison(suffixFirstLine.take(length))
            if (sNorm == xNorm && sNorm.isNotEmpty()) {
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

        // Strip the duplicate bracket and any trailing text that matches the suffix after the bracket
        val afterBracketInSuggestion = suggestion.substring(firstClose + 1)
        val afterBracketInSuffix = suffix.trimStart().drop(1)
        val trailingOverlap = commonPrefixLength(afterBracketInSuggestion, afterBracketInSuffix)

        return suggestion.substring(0, firstClose) +
            afterBracketInSuggestion.drop(trailingOverlap)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val exact = commonPrefixLengthExact(a, b)
        if (exact > 0) return exact

        val aNorm = normalizeForComparison(a)
        val bNorm = normalizeForComparison(b)
        val normalizedMatch = commonPrefixLengthExact(aNorm, bNorm)
        if (normalizedMatch <= 0) return 0

        // Map normalized match length back to original character count
        return mapNormalizedLengthToOriginal(a, normalizedMatch)
    }

    private fun commonPrefixLengthExact(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        for (i in 0 until max) {
            if (a[i] != b[i]) return i
        }
        return max
    }

    private fun mapNormalizedLengthToOriginal(original: String, normalizedLength: Int): Int {
        var normalizedPos = 0
        var originalPos = 0
        val normalized = normalizeForComparison(original)
        while (originalPos < original.length && normalizedPos < normalizedLength) {
            val origChar = original[originalPos]
            if (normalizedPos < normalized.length && normalized[normalizedPos] == origChar) {
                normalizedPos++
            }
            originalPos++
        }
        return originalPos
    }

    private fun normalizeForComparison(text: String): String =
        text.replace(WHITESPACE_RUN, " ")
            .replace(BRACKET_SPACE, "$1")
            .trim()

    private val WHITESPACE_RUN = Regex("\\s+")
    private val BRACKET_SPACE = Regex("\\s*([()\\[\\]{},;:])\\s*")
    private val CLOSING_BRACKETS = setOf(')', ']', '}')
    private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
    private const val MIN_DISPLAY_LENGTH = 2
    private const val MAX_OVERLAP_WINDOW = 200
}
