package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InlineConfidenceScorerTest : BasePlatformTestCase() {

    fun testNormalSuggestionGetsBaselineScore() {
        val score = score("println(value)", prefix = "console.", suffix = "")
        assertTrue("Score should be reasonable: $score", score >= 0.4)
    }

    fun testVeryShortSuggestionPenalized() {
        val score = score("x", prefix = "val ", suffix = "")
        assertTrue("Short suggestion should score lower: $score", score < 0.4)
    }

    fun testSuffixRepeatPenalized() {
        val score = score("return value", prefix = "x = ", suffix = "return value")
        assertTrue("Suffix repeat should score low: $score", score < 0.2)
    }

    fun testPrefixEchoPenalized() {
        val score = score("console.log", prefix = "console.log", suffix = "")
        assertTrue("Prefix echo should score low: $score", score < 0.2)
    }

    fun testBlankSuggestionScoresZero() {
        val score = score("   ", prefix = "val ", suffix = "")
        assertTrue("Blank should score very low: $score", score < 0.3)
    }

    fun testNaturalBoundaryEndingBoosted() {
        val scoreWithBoundary = score("value);", prefix = "console.log(", suffix = "")
        val scoreWithout = score("valu", prefix = "console.log(", suffix = "")
        assertTrue(
            "Boundary ending should score higher: $scoreWithBoundary vs $scoreWithout",
            scoreWithBoundary > scoreWithout
        )
    }

    fun testApiProvidedScorePreserved() {
        val candidate = InlineCompletionCandidate(
            text = "x",
            insertionOffset = 0,
            confidenceScore = 0.95,
        )
        val request = ProviderRequest(prefix = "", suffix = "", filePath = null, language = null)
        val score = InlineConfidenceScorer.score(candidate, request)
        assertEquals(0.95, score, 0.001)
    }

    private fun score(text: String, prefix: String, suffix: String): Double {
        val candidate = InlineCompletionCandidate(text = text, insertionOffset = prefix.length)
        val request = ProviderRequest(prefix = prefix, suffix = suffix, filePath = null, language = null)
        return InlineConfidenceScorer.score(candidate, request)
    }
}
