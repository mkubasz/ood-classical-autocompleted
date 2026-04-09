package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineSuggestionSafetyTest {

    @Test
    fun rejectsMultilineSuggestionInsidePartialPythonSignature() {
        val request = ProviderRequest(
            prefix = "class Agent:\n    def clear_history(self(",
            suffix = "",
            filePath = "agent.py",
            language = "py",
            cursorOffset = "class Agent:\n    def clear_history(self(".length,
        )

        val candidate = InlineCompletionCandidate(
            text = ",\n        str: str) -> None:\n        self._memory.clear()",
            insertionOffset = request.prefix.length,
        )

        assertFalse(InlineSuggestionSafety.isSafe(candidate, request))
    }

    @Test
    fun rejectsMultilineSuggestionWhenCurrentLineHasTrailingCode() {
        val request = ProviderRequest(
            prefix = "result = ",
            suffix = "value",
            filePath = "agent.py",
            language = "py",
            cursorOffset = "result = ".length,
        )

        val candidate = InlineCompletionCandidate(
            text = "foo(\n    bar,\n)",
            insertionOffset = request.prefix.length,
        )

        assertFalse(InlineSuggestionSafety.isSafe(candidate, request))
    }

    @Test
    fun allowsMultilineSuggestionAtBlockBoundary() {
        val request = ProviderRequest(
            prefix = "if ready:",
            suffix = "",
            filePath = "agent.py",
            language = "py",
            cursorOffset = "if ready:".length,
        )

        val candidate = InlineCompletionCandidate(
            text = "\n    return True",
            insertionOffset = request.prefix.length,
        )

        assertTrue(InlineSuggestionSafety.isSafe(candidate, request))
    }

    @Test
    fun allowsBoundaryAdjustedAssignmentOnNextLine() {
        val request = ProviderRequest(
            prefix = "logger.setLevel(logging.DEBUG)",
            suffix = "",
            filePath = "logger.py",
            language = "py",
            cursorOffset = "logger.setLevel(logging.DEBUG)".length,
        )

        val candidate = InlineCompletionCandidate(
            text = "\nhandler = logging.FileHandler('example.log')",
            insertionOffset = request.prefix.length,
        )

        assertTrue(InlineSuggestionSafety.isSafe(candidate, request))
    }

    @Test
    fun allowsBoundaryAdjustedFunctionDefinitionOnNextLine() {
        val request = ProviderRequest(
            prefix = "logger.addHandler(handler)",
            suffix = "",
            filePath = "logger.py",
            language = "py",
            cursorOffset = "logger.addHandler(handler)".length,
        )

        val candidate = InlineCompletionCandidate(
            text = "\ndef log_message(message, level):\n    logger.info(message)",
            insertionOffset = request.prefix.length,
        )

        assertTrue(InlineSuggestionSafety.isSafe(candidate, request))
    }
}
