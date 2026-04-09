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

    fun testReturnsNoMatchWhenEditOverlapsOffCaretSuggestion() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 15,
                insertionOffset = 10,
                text = "await ",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 12,
                oldLength = 0,
                newText = "x",
            ),
        )

        assertEquals(ProgressiveSuggestionUpdate.Outcome.NoMatch, outcome)
    }

    fun testShiftsOffsetsWhenEditIsBeforeOffCaretSuggestion() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 50,
                insertionOffset = 100,
                text = "return value",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 50,
                oldLength = 0,
                newText = "abc",
            ),
        )

        val shifted = outcome as ProgressiveSuggestionUpdate.Outcome.OffsetShifted
        assertEquals(103, shifted.suggestion.insertionOffset)
        assertEquals("return value", shifted.suggestion.text)
    }

    fun testKeepsSuggestionWhenEditIsAfterOffCaretSuggestion() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 200,
                insertionOffset = 100,
                text = "return value",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 200,
                oldLength = 0,
                newText = "z",
            ),
        )

        val shifted = outcome as ProgressiveSuggestionUpdate.Outcome.OffsetShifted
        assertEquals(100, shifted.suggestion.insertionOffset)
        assertEquals("return value", shifted.suggestion.text)
    }

    fun testShiftsOffsetsOnDeletionBeforeOffCaretSuggestion() {
        val outcome = ProgressiveSuggestionUpdate.apply(
            activeSuggestion = ProgressiveSuggestionUpdate.ActiveSuggestion(
                anchorOffset = 50,
                insertionOffset = 100,
                text = "return value",
            ),
            edit = ProgressiveSuggestionUpdate.Edit(
                offset = 40,
                oldLength = 5,
                newText = "",
            ),
        )

        val shifted = outcome as ProgressiveSuggestionUpdate.Outcome.OffsetShifted
        assertEquals(95, shifted.suggestion.insertionOffset)
        assertEquals("return value", shifted.suggestion.text)
    }
}
