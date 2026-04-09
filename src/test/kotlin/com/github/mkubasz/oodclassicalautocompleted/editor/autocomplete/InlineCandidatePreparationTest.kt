package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineCandidatePreparationTest {

    @Test
    fun preservesSingleLineMemberAccessCompletionForClearHistory() {
        val request = AutocompleteRequest(
            prefix = """
                class Agent:
                    def clear_history(self) -> None:
                        self.
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isAfterMemberAccess = true,
                receiverExpression = "self",
                receiverMemberNames = listOf("_history", "clear_history"),
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = "self._history.clear()",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertEquals(listOf("_history.clear()"), prepared.map { it.text })
    }

    @Test
    fun rejectsMalformedInitializerSignatureCompletion() {
        val request = AutocompleteRequest(
            prefix = """
                class Agent:
                    def __init__(
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = "       , model_provider: ModelProvider, *, prompt_builder: PromptBuilder) -> None:\n        self._model_provider = model_provider",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertTrue(prepared.isEmpty())
    }

    @Test
    fun rejectsMalformedPropertySetterCompletion() {
        val request = AutocompleteRequest(
            prefix = """
                @property
                def history(self) -> History:
                    return self._history

                @history.setter
                def history(self
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = ", value: History) -> None:\n        self._history = value",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertTrue(prepared.isEmpty())
    }

    @Test
    fun keepsSingleLinePropertyGetterSignatureContinuation() {
        val request = AutocompleteRequest(
            prefix = """
                @property
                def history(self
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = ") -> History:",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertEquals(listOf(") -> History:"), prepared.map { it.text })
    }

    @Test
    fun repairsExtraClosingDelimiterInClassBaseCompletion() {
        val request = AutocompleteRequest(
            prefix = """
                class Czlowiek:
                    pass

                class Bartek(Czlo
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isClassBaseListLikeContext = true,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
                classBaseReferencePrefix = "Czlo",
                matchingTypeNames = listOf("Czlowiek"),
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = "wiek):)",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertEquals(listOf("wiek):"), prepared.map { it.text })
    }

    @Test
    fun repairsDuplicateClosingParenBeforeColonInClassBaseCompletion() {
        val request = AutocompleteRequest(
            prefix = """
                class Czlowiek:
                    pass

                class Bartek(Czlo
            """.trimIndent(),
            suffix = "",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isClassBaseListLikeContext = true,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
                classBaseReferencePrefix = "Czlo",
                matchingTypeNames = listOf("Czlowiek"),
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = "wiek)):",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertEquals(listOf("wiek):"), prepared.map { it.text })
    }

    @Test
    fun repairsLoggerFunctionDefinitionIntoNewLine() {
        val request = AutocompleteRequest(
            prefix = """
                logger.addHandler(handler)
            """.trimIndent(),
            suffix = "",
            filePath = "logger.py",
            language = "Python",
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = "def log_message(message, level):\n    logger.info(message)",
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertEquals(
            listOf("\ndef log_message(message, level):\n    logger.info(message)"),
            prepared.map { it.text },
        )
    }

    @Test
    fun rejectsTutorialStylePythonBodyBoilerplateInFreshDefinitionBody() {
        val request = AutocompleteRequest(
            prefix = "def my_new_workflow(message: str):",
            suffix = "",
            filePath = "workflow.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                currentDefinitionName = "my_new_workflow",
                currentParameterNames = listOf("message"),
                isFreshBlockBodyContext = true,
            ),
        )

        val prepared = InlineCandidatePreparation.prepare(
            rawCandidates = listOf(
                InlineCompletionCandidate(
                    text = """
                        
                            # Example usage of the calculate_average function
                            numbers = [10, 20, 30]
                            avg = calculate_average(numbers)
                    """.trimIndent(),
                    insertionOffset = request.prefix.length,
                )
            ),
            request = request,
        )

        assertTrue(prepared.isEmpty())
    }
}
