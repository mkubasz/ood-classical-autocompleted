package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteSuggestionNormalizerTest : BasePlatformTestCase() {

    fun testStripsPrefixEchoFromSuggestion() {
        val request = ProviderRequest(
            prefix = "console.lo",
            suffix = "",
            filePath = "demo.js",
            language = "js",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "console.log(value)",
            request = request,
            maxChars = 400,
        )

        assertEquals("g(value)", normalized)
    }

    fun testTrimsOverlapWithExistingSuffix() {
        val request = ProviderRequest(
            prefix = "return ",
            suffix = ")",
            filePath = "demo.kt",
            language = "kt",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "result)",
            request = request,
            maxChars = 400,
        )

        assertEquals("result", normalized)
    }

    fun testExtractsCodeFromMarkdownFence() {
        val request = ProviderRequest(
            prefix = "",
            suffix = "",
            filePath = "demo.py",
            language = "py",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "```python\nprint('hi')\n```",
            request = request,
            maxChars = 400,
        )

        assertEquals("print('hi')", normalized)
    }

    fun testDropsNoOutputSentinel() {
        val request = ProviderRequest(
            prefix = "",
            suffix = "",
            filePath = null,
            language = null,
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "(no output)",
            request = request,
            maxChars = 400,
        )

        assertEquals("", normalized)
    }

    fun testStripsLeadingParenDuplicationWithSuffix() {
        val request = ProviderRequest(
            prefix = "class Ania(Czl",
            suffix = "):\n    pass",
            filePath = "demo.py",
            language = "py",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "):",
            request = request,
            maxChars = 400,
        )

        assertFalse("Should not contain leading paren that duplicates suffix", normalized.startsWith(")"))
    }

    fun testFiltersVeryShortSuggestions() {
        val request = ProviderRequest(
            prefix = "b = ",
            suffix = "",
            filePath = "demo.py",
            language = "py",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "1",
            request = request,
            maxChars = 400,
        )

        assertEquals("", normalized)
    }

    fun testStripsDuplicateClosingParenForGoMethodReceiver() {
        val request = ProviderRequest(
            prefix = "func (",
            suffix = ") Count() int {\n",
            filePath = "counter.go",
            language = "go",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "a *AdditionCounter) Count() int {",
            request = request,
            maxChars = 400,
        )

        assertEquals("a *AdditionCounter", normalized)
    }

    fun testStripsFormattingDifferentSuffixOverlap() {
        val request = ProviderRequest(
            prefix = "class Foo:\n    def process(",
            suffix = "self, data):\n        pass",
            filePath = "demo.py",
            language = "py",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "self,  data ):",
            request = request,
            maxChars = 400,
        )

        assertEquals("", normalized)
    }

    fun testStripsPrefixEchoWithDifferentFormatting() {
        val request = ProviderRequest(
            prefix = "func process( ctx context.Context,",
            suffix = "",
            filePath = "main.go",
            language = "go",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "ctx context.Context, data []byte) error {",
            request = request,
            maxChars = 400,
        )

        assertFalse(
            "Should strip echoed prefix with different spacing: '$normalized'",
            normalized.startsWith("ctx context.Context,")
        )
    }

    fun testStripsDuplicateClosingBracketForArray() {
        val request = ProviderRequest(
            prefix = "items = [",
            suffix = "]\n",
            filePath = "demo.py",
            language = "py",
        )

        val normalized = AutocompleteSuggestionNormalizer.normalize(
            rawText = "1, 2, 3]",
            request = request,
            maxChars = 400,
        )

        assertFalse("Should strip duplicate bracket: '$normalized'", normalized.endsWith("]"))
    }
}
