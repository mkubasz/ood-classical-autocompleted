package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteServiceInteractionTest : BasePlatformTestCase() {

    fun testAcceptNextWordInsertsPrefixAndStoresPendingRemainder() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.textLength)
        service().testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate(
                    text = "calculate_average(numbers)",
                    insertionOffset = editor.caretModel.offset,
                )
            ),
        )

        val accepted = service().acceptNextWord(editor)

        assertTrue(accepted)
        assertEquals("value = calculate_average", editor.document.text)
        val pending = service().testingPendingSuggestion(editor)
        assertNotNull(pending)
        assertEquals("(numbers)", pending?.text)
        assertEquals(editor.document.textLength, pending?.insertionOffset)
    }

    fun testAcceptNextLineKeepsRemainingLinesPending() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(editor.document.textLength)
        service().testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate(
                    text = "first_line()\nsecond_line()",
                    insertionOffset = editor.caretModel.offset,
                )
            ),
        )

        val accepted = service().acceptNextLine(editor)

        assertTrue(accepted)
        assertEquals("value = first_line()", editor.document.text)
        val pending = service().testingPendingSuggestion(editor)
        assertNotNull(pending)
        assertEquals("\nsecond_line()", pending?.text)
        assertEquals(editor.document.textLength, pending?.insertionOffset)
    }

    fun testCyclingAlternativesUpdatesSelectedIndexAndPendingSuggestion() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        val insertionOffset = editor.document.textLength
        service().testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate("alpha", insertionOffset),
                InlineCompletionCandidate("beta", insertionOffset),
                InlineCompletionCandidate("gamma", insertionOffset),
            ),
            currentIndex = 0,
        )

        val movedForward = service().cycleToNextSuggestion(editor)
        val movedBackward = service().cycleToPreviousSuggestion(editor)

        assertTrue(movedForward)
        assertTrue(movedBackward)
        assertTrue(service().hasAlternativeSuggestions(editor))
        assertEquals(0, service().testingInlineState(editor)?.currentIndex)
        assertEquals("alpha", service().testingPendingSuggestion(editor)?.text)
    }

    fun testNextEditUsesTabTabFlowToPreviewAndApply() {
        myFixture.configureByText("sample.txt", "hello world")
        val editor = myFixture.editor
        val startOffset = editor.document.text.indexOf("world")
        val originalText = editor.document.text
        service().testingSeedNextEditState(
            editor = editor,
            candidate = NextEditCompletionCandidate(
                startOffset = startOffset,
                endOffset = startOffset + "world".length,
                replacementText = "planet",
            ),
        )

        val previewed = service().acceptOnTab(editor)

        assertTrue(previewed)
        assertEquals(originalText, editor.document.text)
        assertTrue(service().testingHasNextEditPreview(editor))

        val applied = service().acceptOnTab(editor)

        assertTrue(applied)
        assertEquals("hello planet", editor.document.text)
        assertFalse(service().hasActiveSuggestion(editor))
    }

    fun testRejectSuggestionDismissesNextEditPreviewWithoutApplying() {
        myFixture.configureByText("sample.txt", "hello world")
        val editor = myFixture.editor
        val startOffset = editor.document.text.indexOf("world")
        val originalText = editor.document.text
        service().testingSeedNextEditState(
            editor = editor,
            candidate = NextEditCompletionCandidate(
                startOffset = startOffset,
                endOffset = startOffset + "world".length,
                replacementText = "planet",
            ),
        )
        assertTrue(service().acceptOnTab(editor))
        assertTrue(service().testingHasNextEditPreview(editor))

        val rejected = service().rejectSuggestion(editor)

        assertTrue(rejected)
        assertEquals(originalText, editor.document.text)
        assertFalse(service().hasActiveSuggestion(editor))
    }

    private fun service(): AutocompleteService = project.service()
}
