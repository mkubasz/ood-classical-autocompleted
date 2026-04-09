package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BaseInlineContextHeuristicsTest : BasePlatformTestCase() {

    fun testRecognizesKotlinDataClassHeaderBeforeConstructorParens() {
        myFixture.configureByText(
            "Person.kt",
            "data class My",
        )

        val document = myFixture.editor.document
        val context = BaseInlineContextHeuristics.build(
            request = InlineContextRequest(
                project = project,
                document = document,
                documentText = document.text,
                caretOffset = document.textLength,
                filePath = "Person.kt",
                languageId = "kotlin",
            ),
            includeResolvedDefinitions = true,
        )

        assertTrue("context=$context", context.isDefinitionHeaderLikeContext)
        assertFalse("context=$context", context.isInParameterListLikeContext)
    }
}
