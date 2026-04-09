package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteSuggestionNormalizerTest : BasePlatformTestCase() {

    fun testStripsPrefixEchoFromSuggestion() {
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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
        val request = AutocompleteRequest(
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

        assertFalse(
            "Should not have duplicate closing paren: '$normalized'",
            normalized.contains(") Count") && request.suffix.startsWith(")"),
        )
    }

    fun testStripsDuplicateClosingBracketForArray() {
        val request = AutocompleteRequest(
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
