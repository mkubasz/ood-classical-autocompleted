package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SuggestionAlternativesTest : BasePlatformTestCase() {

    fun testPrimarySuggestionMovesToFrontAndDeduplicates() {
        val context = SuggestionAlternatives.Context(documentHash = 1, anchorOffset = 10)
        val state = SuggestionAlternatives.State(
            context = context,
            suggestions = listOf(
                SuggestionAlternatives.Suggestion(10, "first"),
                SuggestionAlternatives.Suggestion(8, "second"),
            ),
            selectedIndex = 1,
        )

        val updated = state.withPrimarySuggestion(SuggestionAlternatives.Suggestion(8, "second"))

        assertEquals(
            listOf(
                SuggestionAlternatives.Suggestion(8, "second"),
                SuggestionAlternatives.Suggestion(10, "first"),
            ),
            updated.suggestions,
        )
        assertEquals(0, updated.selectedIndex)
    }

    fun testAdditionalSuggestionAppendsWhenUnique() {
        val context = SuggestionAlternatives.Context(documentHash = 1, anchorOffset = 10)
        val state = SuggestionAlternatives.State.single(
            context = context,
            suggestion = SuggestionAlternatives.Suggestion(10, "first"),
        )

        val updated = state.withAdditionalSuggestion(SuggestionAlternatives.Suggestion(8, "second"))

        assertEquals(
            listOf(
                SuggestionAlternatives.Suggestion(10, "first"),
                SuggestionAlternatives.Suggestion(8, "second"),
            ),
            updated.suggestions,
        )
        assertEquals(0, updated.selectedIndex)
    }

    fun testCycleWrapsForwardAndBackward() {
        val context = SuggestionAlternatives.Context(documentHash = 1, anchorOffset = 10)
        val state = SuggestionAlternatives.State(
            context = context,
            suggestions = listOf(
                SuggestionAlternatives.Suggestion(10, "first"),
                SuggestionAlternatives.Suggestion(8, "second"),
                SuggestionAlternatives.Suggestion(10, "third"),
            ),
        )

        val forward = state.cycle(1)
        val backward = forward.cycle(-1)
        val wrapped = state.cycle(-1)

        assertEquals("second", forward.current.text)
        assertEquals("first", backward.current.text)
        assertEquals("third", wrapped.current.text)
    }
}
