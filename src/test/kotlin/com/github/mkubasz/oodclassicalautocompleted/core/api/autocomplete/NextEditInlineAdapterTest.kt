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

        val region = NextEditInlineAdapter.extractRegion(
            request = request,
            linesAboveCursor = 2,
            linesBelowCursor = 2,
        )

        assertTrue(region.text.contains("pri"))
        assertEquals(
            "fun main() {\n    pri<|cursor|>ntln(\"hi\")\n}",
            NextEditInlineAdapter.renderWithCursor(region),
        )
    }

    fun testExtractRegionUsesAsymmetricLineWindow() {
        val lines = (1..20).joinToString("\n") { "line$it" }
        val request = AutocompleteRequest(
            prefix = lines.substringBefore("line10") + "line10",
            suffix = lines.substringAfter("line10"),
            filePath = "Main.kt",
            language = "kt",
            cursorOffset = lines.indexOf("line10") + "line10".length,
        )

        val region = NextEditInlineAdapter.extractRegion(
            request = request,
            linesAboveCursor = 2,
            linesBelowCursor = 4,
        )

        assertTrue(region.before.contains("line7"))
        assertTrue(region.text.contains("line8"))
        assertTrue(region.text.contains("line14"))
        assertFalse(region.text.contains("line7"))
        assertFalse(region.text.contains("line15"))
    }

    fun testDerivesSingleRangeEditWhenOnlyCursorGapChanges() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "    pri",
            after = "",
            startOffset = 0,
            cursorOffset = "    pri".length,
        )

        val edit = NextEditInlineAdapter.deriveEdit(region, "    print(value)")

        assertEquals("nt(value)", edit?.replacementText)
        assertEquals("    pri".length, edit?.startOffset)
        assertEquals("    pri".length, edit?.endOffset)
    }

    fun testDerivesSingleRangeEditEarlierInEditableSpan() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "return foo()",
            after = "",
            startOffset = 100,
            cursorOffset = "return foo()".length,
        )

        val edit = NextEditInlineAdapter.deriveEdit(region, "return await foo()")

        assertEquals("await ", edit?.replacementText)
        assertEquals(107, edit?.startOffset)
        assertEquals(107, edit?.endOffset)
    }

    fun testDerivesWholeSpanReplacementWhenResponseRewritesOriginalText() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "value = oldCall()",
            after = "",
            startOffset = 0,
            cursorOffset = "value = ".length,
        )

        val edit = NextEditInlineAdapter.deriveEdit(region, "result = newCall()")

        assertEquals(0, edit?.startOffset)
        assertEquals("value = old".length, edit?.endOffset)
        assertEquals("result = new", edit?.replacementText)
    }

    fun testDerivesInlineInsertionForSingleLineCaretCompletion() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "def clear_memory(self) -> None:\n    self.",
            after = "",
            startOffset = 0,
            cursorOffset = "def clear_memory(self) -> None:\n    self.".length,
        )

        val insertion = NextEditInlineAdapter.deriveInlineInsertion(
            region,
            "def clear_memory(self) -> None:\n    self._memory.clear()",
        )

        assertEquals("_memory.clear()", insertion?.text)
        assertEquals(region.cursorOffset, insertion?.offset)
    }

    fun testRejectsMultilineInlineInsertionEvenAtEndOfDocument() {
        val region = NextEditInlineAdapter.EditableRegion(
            before = "",
            text = "a = lambda x: x  # noqa: E731\nclass A:\n    def __init__(self):\n        pass\n\n    def a(self,",
            after = "",
            startOffset = 0,
            cursorOffset = "a = lambda x: x  # noqa: E731\nclass A:\n    def __init__(self):\n        pass\n\n    def a(self,".length,
        )

        val insertion = NextEditInlineAdapter.deriveInlineInsertion(
            region,
            "a = lambda x: x  # noqa: E731\nclass A:\n    def __init__(self):\n        pass\n\n    def a(self,):\n       :):\n pass",
        )

        assertNull(insertion)
    }
}
