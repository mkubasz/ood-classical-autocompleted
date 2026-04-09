package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineSuggestionBoundaryAdjusterTest {

    @Test
    fun insertsLeadingNewlineBeforeAssignmentAfterClosedStatement() {
        val request = ProviderRequest(
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
        val request = ProviderRequest(
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
        val request = ProviderRequest(
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

    @Test
    fun doesNotInsertNewlineWhenCompletingIncompleteDefHeader() {
        val request = ProviderRequest(
            prefix = "def proje",
            suffix = "",
            filePath = "demo.py",
            language = "py",
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "ct_streaming_workflow(",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("ct_streaming_workflow(", adjusted.text)
    }

    @Test
    fun doesNotInsertNewlineWhenCompletingIncompleteClassHeader() {
        val request = ProviderRequest(
            prefix = "class MyMod",
            suffix = "",
            filePath = "models.py",
            language = "py",
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "el(BaseModel):",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("el(BaseModel):", adjusted.text)
    }

    @Test
    fun doesNotInsertNewlineWhenCompletingTokenInsideFunctionCall() {
        val request = ProviderRequest(
            prefix = "                metadata=RecordedMessageMetad",
            suffix = "",
            filePath = "demo.py",
            language = "py",
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "ata(",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("ata(", adjusted.text)
    }

    @Test
    fun doesNotInsertNewlineForKeywordArgCompletion() {
        val request = ProviderRequest(
            prefix = "        result = process_da",
            suffix = "",
            filePath = "demo.py",
            language = "py",
        )

        val adjusted = InlineSuggestionBoundaryAdjuster.adjust(
            candidate = InlineCompletionCandidate(
                text = "ta(input_file)",
                insertionOffset = request.prefix.length,
            ),
            request = request,
        )

        assertEquals("ta(input_file)", adjusted.text)
    }
}
