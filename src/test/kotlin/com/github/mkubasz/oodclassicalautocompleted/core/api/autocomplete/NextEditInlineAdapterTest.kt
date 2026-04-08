package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NextEditInlineAdapterTest : BasePlatformTestCase() {

    fun testExtractRegionPlacesCursorInsideEditableSlice() {
        val request = AutocompleteRequest(
            prefix = "fun main() {\n    pri",
            suffix = "ntln(\"hi\")\n}",
            filePath = "Main.kt",
            language = "kt",
            cursorOffset = "fun main() {\n    pri".length,
        )

        val region = NextEditInlineAdapter.extractRegion(request, radius = 2)

        assertTrue(region.text.contains("pri"))
        assertEquals(
            "fun main() {\n    pri<|cursor|>ntln(\"hi\")\n}",
            NextEditInlineAdapter.renderWithCursor(region),
        )
    }

    fun testDerivesInlineInsertionWhenOnlyCursorGapChanges() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "    pri",
            after = "",
            startOffset = 0,
            cursorOffset = "    pri".length,
        )

        val insertion = NextEditInlineAdapter.deriveInsertion(region, "    print(value)")

        assertEquals("nt(value)", insertion?.text)
        assertEquals("    pri".length, insertion?.offset)
    }

    fun testDerivesInlineInsertionEarlierInEditableSpan() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "return foo()",
            after = "",
            startOffset = 100,
            cursorOffset = "return foo()".length,
        )

        val insertion = NextEditInlineAdapter.deriveInsertion(region, "return await foo()")

        assertEquals("await ", insertion?.text)
        assertEquals(107, insertion?.offset)
    }

    fun testReturnsNullWhenResponseRewritesOutsideSafeInsertionShape() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "value = oldCall()",
            after = "",
            startOffset = 0,
            cursorOffset = "value = ".length,
        )

        val insertion = NextEditInlineAdapter.deriveInsertion(region, "result = newCall()")

        assertNull(insertion)
    }
}
