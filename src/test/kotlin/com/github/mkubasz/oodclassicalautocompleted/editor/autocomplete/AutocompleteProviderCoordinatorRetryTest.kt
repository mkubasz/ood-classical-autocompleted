package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.CompletionResponse
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteProviderCoordinatorRetryTest : BasePlatformTestCase() {

    fun testRetriesFimOnceForInvalidPythonClassHeaderCandidate() {
        myFixture.configureByText(
            "agent.py",
            """
            class Czlowiek:
                pass

            class Bartek(Czlo
            """.trimIndent(),
        )
        if (!isPythonPsiAvailable()) return

        val documentText = myFixture.editor.document.text
        val request = AutocompleteRequest(
            prefix = documentText,
            suffix = "",
            filePath = "agent.py",
            language = myFixture.file.language.id,
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isClassBaseListLikeContext = true,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
                classBaseReferencePrefix = "Czlo",
                matchingTypeNames = listOf("Czlowiek"),
            ),
        )
        val snapshot = CompletionContextSnapshot(
            filePath = request.filePath,
            language = request.language,
            documentText = documentText,
            documentStamp = 1L,
            caretOffset = request.prefix.length,
            prefix = request.prefix,
            suffix = request.suffix,
            prefixWindow = request.prefix,
            suffixWindow = request.suffix,
            inlineContext = request.inlineContext,
            project = project,
        )

        val selection = AutocompleteProviderCoordinator.firstValidInlineSelection(
            snapshot = snapshot,
            request = request,
            responsesInCompletionOrder = listOf(
                AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "fim",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "wiek) -> None:",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                )
            ),
            retryResponsesBySource = mapOf(
                "fim" to AutocompleteProviderCoordinator.ProviderInlineResponse(
                    sourceName = "fim_retry",
                    response = CompletionResponse(
                        inlineCandidates = listOf(
                            InlineCompletionCandidate(
                                text = "wiek):",
                                insertionOffset = request.prefix.length,
                            )
                        )
                    ),
                )
            ),
        )

        assertEquals("fim_retry", selection?.sourceName)
        assertEquals(listOf("wiek):"), selection?.candidates?.map { it.text })
    }

    private fun isPythonPsiAvailable(): Boolean {
        val language = myFixture.file.language
        return language.id.contains("python", ignoreCase = true) ||
            language.displayName.contains("python", ignoreCase = true)
    }
}
