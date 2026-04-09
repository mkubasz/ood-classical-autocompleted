package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineSuggestionBoundaryAdjusterTest {

    @Test
    fun insertsLeadingNewlineBeforeAssignmentAfterClosedStatement() {
        val request = AutocompleteRequest(
            prefix = "logger.setLevel(logging.DEBUG)",
            suffix = "",
            filePath = "logger.py",
            language = "py",
            cursorOffset = "logger.setLevel(logging.DEBUG)".length,
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "handler = logging.FileHandler('example.log')",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("\nhandler = logging.FileHandler('example.log')", adjusted.text)
    }

    @Test
    fun insertsLeadingNewlineBeforeFunctionDefinitionAfterClosedStatement() {
        val request = AutocompleteRequest(
            prefix = "logger.addHandler(handler)",
            suffix = "",
            filePath = "logger.py",
            language = "py",
            cursorOffset = "logger.addHandler(handler)".length,
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "def log_message(message, level):\n    logger.info(message)",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("\ndef log_message(message, level):\n    logger.info(message)", adjusted.text)
    }

    @Test
    fun keepsRegularSameLineCompletionUnchanged() {
        val request = AutocompleteRequest(
            prefix = "handler = logging.File",
            suffix = "",
            filePath = "logger.py",
            language = "py",
            cursorOffset = "handler = logging.File".length,
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "Handler('example.log')",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("Handler('example.log')", adjusted.text)
    }
}
