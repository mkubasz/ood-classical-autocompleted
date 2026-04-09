package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedSuggestionTrackerTest {

    @Test
    fun recordsRecentDeletionAsRevertedAcceptedText() {
        val tracker = AcceptedSuggestionTracker(revertWindowMs = 1_000)
        tracker.record(
            kind = AcceptedSuggestionKind.INLINE,
            startOffset = 10,
            insertedText = "value",
            acceptedAt = 100L,
        )

        val reverted = tracker.onDocumentChanged(
            changeOffset = 12,
            oldLength = 3,
            newText = "",
            now = 200L,
        )

        assertEquals(AcceptedSuggestionKind.INLINE, reverted?.kind)
        assertEquals(3, reverted?.deletedChars)
        assertEquals(100L, reverted?.ageMs)
        assertTrue(tracker.activeRanges().isEmpty())
    }

    @Test
    fun shiftsTrackedOffsetsWhenEditsHappenBeforeAcceptedText() {
        val tracker = AcceptedSuggestionTracker(revertWindowMs = 1_000)
        tracker.record(
            kind = AcceptedSuggestionKind.INLINE_LINE,
            startOffset = 10,
            insertedText = "result",
            acceptedAt = 100L,
        )

        val noRevert = tracker.onDocumentChanged(
            changeOffset = 4,
            oldLength = 0,
            newText = "abc",
            now = 150L,
        )

        assertNull(noRevert)
        assertEquals(13, tracker.activeRanges().single().startOffset)
        assertEquals(19, tracker.activeRanges().single().endOffset)
    }

    @Test
    fun ignoresExpiredAcceptedText() {
        val tracker = AcceptedSuggestionTracker(revertWindowMs = 100)
        tracker.record(
            kind = AcceptedSuggestionKind.NEXT_EDIT,
            startOffset = 3,
            insertedText = "replacement",
            acceptedAt = 100L,
        )

        val reverted = tracker.onDocumentChanged(
            changeOffset = 3,
            oldLength = 4,
            newText = "",
            now = 250L,
        )

        assertNull(reverted)
        assertTrue(tracker.activeRanges().isEmpty())
    }

    @Test
    fun doesNotEmitRevertForNonDeletingOverlap() {
        val tracker = AcceptedSuggestionTracker(revertWindowMs = 1_000)
        tracker.record(
            kind = AcceptedSuggestionKind.INLINE_WORD,
            startOffset = 5,
            insertedText = "alpha",
            acceptedAt = 100L,
        )

        val reverted = tracker.onDocumentChanged(
            changeOffset = 6,
            oldLength = 2,
            newText = "zz",
            now = 180L,
        )

        assertNull(reverted)
        assertTrue(tracker.activeRanges().isEmpty())
    }
}
