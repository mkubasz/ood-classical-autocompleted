package com.github.mkubasz.oodclassicalautocompleted.editor.actions

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteActionIntegrationTest : BasePlatformTestCase() {

    fun testAcceptCompletionActionRunsTabTabNextEditFlow() {
        myFixture.configureByText("sample.txt", "hello world")
        val editor = myFixture.editor
        val service = project.service<AutocompleteService>()
        val startOffset = editor.document.text.indexOf("world")
        service.testingSeedNextEditState(
            editor = editor,
            candidate = NextEditCompletionCandidate(
                startOffset = startOffset,
                endOffset = startOffset + "world".length,
                replacementText = "planet",
            ),
        )

        val action = AcceptCompletionAction()
        val updateEvent = actionEvent()
        action.update(updateEvent)
        assertTrue(updateEvent.presentation.isEnabled)

        action.actionPerformed(actionEvent())
        assertTrue(service.testingHasNextEditPreview(editor))

        action.actionPerformed(actionEvent())
        assertEquals("hello planet", editor.document.text)
        assertFalse(service.hasActiveSuggestion(editor))
    }

    fun testAcceptNextWordActionUsesActionLayerAndEnablement() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        val service = project.service<AutocompleteService>()
        service.testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate(
                    text = "calculate_average(numbers)",
                    insertionOffset = editor.document.textLength,
                )
            ),
        )

        val action = AcceptNextWordAction()
        val updateEvent = actionEvent()
        action.update(updateEvent)
        assertTrue(updateEvent.presentation.isEnabled)

        action.actionPerformed(actionEvent())

        assertEquals("value = calculate_average", editor.document.text)
        assertEquals("(numbers)", service.testingPendingSuggestion(editor)?.text)
    }

    fun testAcceptNextLineActionUsesActionLayerAndEnablement() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        val service = project.service<AutocompleteService>()
        service.testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate(
                    text = "first_line()\nsecond_line()",
                    insertionOffset = editor.document.textLength,
                )
            ),
        )

        val action = AcceptNextLineAction()
        val updateEvent = actionEvent()
        action.update(updateEvent)
        assertTrue(updateEvent.presentation.isEnabled)

        action.actionPerformed(actionEvent())

        assertEquals("value = first_line()", editor.document.text)
        assertEquals("\nsecond_line()", service.testingPendingSuggestion(editor)?.text)
    }

    fun testRejectActionDismissesPreviewFromActionLayer() {
        myFixture.configureByText("sample.txt", "hello world")
        val editor = myFixture.editor
        val service = project.service<AutocompleteService>()
        val startOffset = editor.document.text.indexOf("world")
        service.testingSeedNextEditState(
            editor = editor,
            candidate = NextEditCompletionCandidate(
                startOffset = startOffset,
                endOffset = startOffset + "world".length,
                replacementText = "planet",
            ),
        )
        assertTrue(service.acceptOnTab(editor))

        val action = RejectCompletionAction()
        val updateEvent = actionEvent()
        action.update(updateEvent)
        assertTrue(updateEvent.presentation.isEnabled)

        action.actionPerformed(actionEvent())

        assertFalse(service.hasActiveSuggestion(editor))
        assertEquals("hello world", editor.document.text)
    }

    fun testCycleActionsUseRealActionEventsAndUpdateEnablement() {
        myFixture.configureByText("sample.txt", "value = ")
        val editor = myFixture.editor
        val service = project.service<AutocompleteService>()
        service.testingSeedInlineState(
            editor = editor,
            candidates = listOf(
                InlineCompletionCandidate("alpha", editor.document.textLength),
                InlineCompletionCandidate("beta", editor.document.textLength),
                InlineCompletionCandidate("gamma", editor.document.textLength),
            ),
        )

        val nextAction = CycleNextSuggestionAction()
        val previousAction = CyclePreviousSuggestionAction()

        val nextUpdate = actionEvent()
        nextAction.update(nextUpdate)
        assertTrue(nextUpdate.presentation.isEnabled)

        val previousUpdate = actionEvent()
        previousAction.update(previousUpdate)
        assertTrue(previousUpdate.presentation.isEnabled)

        nextAction.actionPerformed(actionEvent())
        assertEquals("beta", service.testingPendingSuggestion(editor)?.text)
        previousAction.actionPerformed(actionEvent())
        assertEquals("alpha", service.testingPendingSuggestion(editor)?.text)
    }

    private fun actionEvent(): AnActionEvent {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .build()
        return AnActionEvent.createFromDataContext(
            ActionPlaces.UNKNOWN,
            Presentation(),
            dataContext,
        )
    }
}
