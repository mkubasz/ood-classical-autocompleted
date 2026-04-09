package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteTriggerHeuristicsTest : BasePlatformTestCase() {

    fun testSuppressesImportContext() {
        val documentText = "import java.util.Lis"

        val decision = evaluate(documentText, documentText.length)

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.IMPORT_CONTEXT, decision.reason)
    }

    fun testSuppressesLineCommentContext() {
        val documentText = "fun main() {\n    // explain this branch"

        val decision = evaluate(documentText, documentText.length)

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.COMMENT_CONTEXT, decision.reason)
    }

    fun testSuppressesBlockCommentContext() {
        val documentText = "/* pending explanation"

        val decision = evaluate(documentText, documentText.length)

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.COMMENT_CONTEXT, decision.reason)
    }

    fun testSuppressesStringContext() {
        val documentText = "val name = \"clas"

        val decision = evaluate(documentText, documentText.length)

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.STRING_CONTEXT, decision.reason)
    }

    fun testSuppressesGeneratedFileByPath() {
        val decision = evaluate(
            documentText = "fun generated() = Unit",
            offset = "fun generated() = Unit".length,
            filePath = "/tmp/project/build/generated/source/Main.kt",
        )

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.GENERATED_FILE, decision.reason)
    }

    fun testSuppressesGeneratedFileByHeaderMarker() {
        val documentText = "// AUTO-GENERATED. DO NOT EDIT.\nfun generated() = Unit"

        val decision = evaluate(documentText, documentText.length, filePath = "/tmp/project/src/Main.kt")

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.GENERATED_FILE, decision.reason)
    }

    fun testAllowsHighSignalNewlineWhitespaceContext() {
        val documentText = "\n" + " ".repeat(120)

        val decision = evaluate(documentText, 60)

        assertTrue(decision.shouldRequest)
    }

    fun testSuppressesLowSignalWhitespaceWithoutBoundarySignal() {
        val documentText = " ".repeat(200)

        val decision = evaluate(documentText, 100)

        assertFalse(decision.shouldRequest)
        assertEquals(AutocompleteTriggerHeuristics.Reason.LOW_SIGNAL_WHITESPACE, decision.reason)
    }

    fun testAllowsNormalCodeContext() {
        val documentText = "fun main() = pri"

        val decision = evaluate(documentText, documentText.length)

        assertTrue(decision.shouldRequest)
        assertNull(decision.reason)
    }

    private fun evaluate(
        documentText: String,
        offset: Int,
        filePath: String? = "/tmp/project/src/Main.kt",
    ): AutocompleteTriggerHeuristics.Decision =
        AutocompleteTriggerHeuristics.evaluate(
            documentText = documentText,
            offset = offset,
            filePath = filePath,
            maxDocumentChars = 250_000,
            lookbackForNonWhitespace = 80,
        )
}
