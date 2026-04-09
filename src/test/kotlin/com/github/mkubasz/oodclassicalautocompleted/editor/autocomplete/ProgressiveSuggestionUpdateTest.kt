package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ProgressiveSuggestionUpdateTest : BasePlatformTestCase() {

    fun testTrimsSuggestionWhenTypedTextMatchesPrefix() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 12,
                insertionOffset = 12,
                text = "ntln(value)",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 12,
                oldLength = 0,
                newText = "n",
            ),
        )

        val updated = outcome as ProgressiveSuggestionUpdate.Outcome.Updated
        assertEquals(13, updated.suggestion.anchorOffset)
        assertEquals(13, updated.suggestion.insertionOffset)
        assertEquals("tln(value)", updated.suggestion.text)
    }

    fun testConsumesSuggestionWhenTypedTextCoversRemainder() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 4,
                insertionOffset = 4,
                text = "true",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 4,
                oldLength = 0,
                newText = "true",
            ),
        )

        assertEquals(ProgressiveSuggestionUpdate.Outcome.Consumed, outcome)
    }

    fun testReturnsNoMatchWhenTypedTextDiverges() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 20,
                insertionOffset = 20,
                text = "value)",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 20,
                oldLength = 0,
                newText = "x",
            ),
        )

        assertEquals(ProgressiveSuggestionUpdate.Outcome.NoMatch, outcome)
    }

    fun testReturnsNoMatchForSuggestionsInsertedAwayFromCaret() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 15,
                insertionOffset = 10,
                text = "await ",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 15,
                oldLength = 0,
                newText = "a",
            ),
        )

        assertEquals(ProgressiveSuggestionUpdate.Outcome.NoMatch, outcome)
    }
}
