package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate

internal object InlineConfidenceScorer {

    fun score(candidate: InlineCompletionCandidate, request: ProviderRequest): Double {
        if (candidate.confidenceScore != null) return candidate.confidenceScore
        val text = candidate.text
        if (text.isBlank()) return 0.0

        var score = BASE_SCORE

        val lastChar = text.trimEnd().lastOrNull()
        if (lastChar in NATURAL_BOUNDARIES) score += 0.10

        if (text.length < MIN_USEFUL_LENGTH) score -= 0.15

        val suffixHead = request.suffix.take(text.length)
        if (suffixHead == text) score -= 0.4

        if (text.trim().isEmpty()) score -= 0.3

        val prefixTail = request.prefix.takeLast(text.length)
        if (prefixTail == text) score -= 0.35

        return score.coerceIn(0.0, 1.0)
    }

    private const val BASE_SCORE = 0.5
    private const val MIN_USEFUL_LENGTH = 3
    private val NATURAL_BOUNDARIES = setOf(';', '}', ')', ']', ':', '\n')
}
