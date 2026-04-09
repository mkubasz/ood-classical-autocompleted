package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InlineHeaderPsiValidatorTest : BasePlatformTestCase() {

    fun testIgnoresNormalIncompleteAssignmentContext() {
        myFixture.configureByText(
            "logger.py",
            """
            import logging

            logger = 
            """.trimIndent(),
        )
        if (!isPythonPsiAvailable()) return

        val documentText = myFixture.editor.document.text
        val request = ProviderRequest(
            prefix = documentText,
            suffix = "",
            filePath = "logger.py",
            language = myFixture.file.language.id,
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
            ),
        )

        val result = InlineHeaderPsiValidator.validate(
            candidate = InlineCompletionCandidate(
                text = "logging.getLogger(__name__)",
                insertionOffset = documentText.length,
            ),
            request = request,
            snapshot = snapshotFor(request, documentText),
        )

        assertEquals(InlineHeaderPsiValidator.Result.Valid, result)
    }

    fun testValidatesOnlyHeaderFragmentNotEarlierFileErrors() {
        myFixture.configureByText(
            "agent.py",
            """
            import logging

            logger =

            class Czlowiek:
                pass

            class Bartek(Czlo
            """.trimIndent(),
        )
        if (!isPythonPsiAvailable()) return

        val documentText = myFixture.editor.document.text
        val request = ProviderRequest(
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

        val result = InlineHeaderPsiValidator.validate(
            candidate = InlineCompletionCandidate(
                text = "wiek):",
                insertionOffset = documentText.length,
            ),
            request = request,
            snapshot = snapshotFor(request, documentText),
        )

        assertEquals(InlineHeaderPsiValidator.Result.Valid, result)
    }

    private fun snapshotFor(
        request: ProviderRequest,
        documentText: String,
    ): CompletionContextSnapshot = CompletionContextSnapshot(
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

    private fun isPythonPsiAvailable(): Boolean {
        val language = myFixture.file.language
        return language.id.contains("python", ignoreCase = true) ||
            language.displayName.contains("python", ignoreCase = true)
    }
}
