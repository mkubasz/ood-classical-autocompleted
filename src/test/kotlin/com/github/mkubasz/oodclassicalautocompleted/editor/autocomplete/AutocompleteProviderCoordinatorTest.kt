package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteCapability
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.CompletionResponse
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import org.junit.Assert.assertEquals
import org.junit.Test

class AutocompleteProviderCoordinatorTest {

    @Test
    fun waitsForSlowerFimWhenFastNextEditInlineCandidateIsRejected() {
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
        val snapshot = snapshotFor(request)

        val selection = AutocompleteProviderCoordinator.firstValidInlineSelection(
            snapshot = snapshot,
            request = request,
            responsesInCompletionOrder = listOf(
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "next_edit",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = ", value: History) -> None:\n        self._history = value",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "fim",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = ") -> History:",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
            ),
        )

        assertEquals("fim", selection?.sourceName)
        assertEquals(listOf(") -> History:"), selection?.candidates?.map { it.text })
    }

    @Test
    fun prefersFirstValidNextEditInlineResponseForMemberAccess() {
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
            ),
        )
        val snapshot = snapshotFor(request)

        val selection = AutocompleteProviderCoordinator.firstValidInlineSelection(
            snapshot = snapshot,
            request = request,
            responsesInCompletionOrder = listOf(
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "next_edit",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "self._history.clear()",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "fim",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "_history.append(item)",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
            ),
        )

        assertEquals("next_edit", selection?.sourceName)
        assertEquals(listOf("_history.clear()"), selection?.candidates?.map { it.text })
    }

    @Test
    fun skipsFirstResponseWhenSuggestionAlreadyExistsInSuffix() {
        val request = AutocompleteRequest(
            prefix = "return ",
            suffix = "value",
            filePath = "agent.py",
            language = "Python",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
            ),
        )
        val snapshot = snapshotFor(request)

        val selection = AutocompleteProviderCoordinator.firstValidInlineSelection(
            snapshot = snapshot,
            request = request,
            responsesInCompletionOrder = listOf(
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "fim",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "value",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "next_edit",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "result",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                ),
            ),
        )

        assertEquals("next_edit", selection?.sourceName)
        assertEquals(listOf("result"), selection?.candidates?.map { it.text })
    }

    private fun snapshotFor(request: AutocompleteRequest): CompletionContextSnapshot {
        val documentText = request.prefix + request.suffix
        return CompletionContextSnapshot(
            filePath = request.filePath,
            language = request.language,
            documentText = documentText,
            documentStamp = 1L,
            caretOffset = request.prefix.length,
            prefix = request.prefix,
            suffix = request.suffix,
            prefixWindow = request.prefix.takeLast(1_500),
            suffixWindow = request.suffix.take(1_500),
            inlineContext = request.inlineContext,
        )
    }
}
