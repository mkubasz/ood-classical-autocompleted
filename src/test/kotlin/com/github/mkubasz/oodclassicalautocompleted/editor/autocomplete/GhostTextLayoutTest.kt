package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GhostTextLayoutTest : BasePlatformTestCase() {

    fun testKeepsSingleLineSuggestionsInline() {
        val layout = GhostTextLayout.fromSuggestion("print(value)")

        assertEquals("print(value)", layout.inlineText)
        assertEmpty(layout.blockLines)
    }

    fun testSplitsMultilineSuggestionIntoInlineAndBlockParts() {
        val layout = GhostTextLayout.fromSuggestion("value:\n    one()\n    two()")

        assertEquals("value:", layout.inlineText)
        assertEquals(listOf("    one()", "    two()"), layout.blockLines)
    }

    fun testPreservesTrailingBlankLineInBlockLayout() {
        val layout = GhostTextLayout.fromSuggestion("value:\n    one()\n")

        assertEquals("value:", layout.inlineText)
        assertEquals(listOf("    one()", ""), layout.blockLines)
    }
}
