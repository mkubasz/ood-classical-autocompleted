package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class HeuristicInlineContextProviderTest {

    @Test
    fun extractsReceiverAndDefinitionSignalsFromPlainText() {
        val documentText = """
            class Agent:
                def clear_history(self):
                    logger.
        """.trimIndent()

        val context = HeuristicInlineContextProvider.build(
            request = InlineContextRequest(
                project = unusedProject(),
                document = unusedDocument(),
                documentText = documentText,
                caretOffset = documentText.indexOf("logger.") + "logger.".length,
                filePath = "agent.py",
                languageId = "python",
            )
        )

        assertNotNull(context)
        assertEquals("logger", context?.receiverExpression)
        assertTrue(context?.isAfterMemberAccess == true)
        assertTrue(context?.enclosingNames?.contains("Agent") == true)
        assertTrue(context?.enclosingNames?.contains("clear_history") == true)
    }

    @Test
    fun extractsClassBaseSignalsAndMatchingTypes() {
        val documentText = """
            class Czlowiek:
                pass

            class Bartek(Czlo
        """.trimIndent()

        val context = HeuristicInlineContextProvider.build(
            request = InlineContextRequest(
                project = unusedProject(),
                document = unusedDocument(),
                documentText = documentText,
                caretOffset = documentText.length,
                filePath = "agent.py",
                languageId = "python",
            )
        )

        assertNotNull(context)
        assertTrue(context?.isClassBaseListLikeContext == true)
        assertEquals("Czlo", context?.classBaseReferencePrefix)
        assertTrue(context?.matchingTypeNames?.contains("Czlowiek") == true)
    }

    @Test
    fun marksFreshPythonDefinitionBodyAndCapturesParameters() {
        val documentText = "def my_new_workflow(message: str):"

        val context = HeuristicInlineContextProvider.build(
            request = InlineContextRequest(
                project = unusedProject(),
                document = unusedDocument(),
                documentText = documentText,
                caretOffset = documentText.length,
                filePath = "workflow.py",
                languageId = "python",
            )
        )

        assertNotNull(context)
        assertEquals("my_new_workflow", context?.currentDefinitionName)
        assertEquals(listOf("message"), context?.currentParameterNames)
        assertTrue(context?.isFreshBlockBodyContext == true)
        assertTrue(context?.resolvedDefinitions?.isEmpty() == true)
    }

    private fun unusedProject(): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException("Not used") } as Project

    private fun unusedDocument(): Document = Proxy.newProxyInstance(
        Document::class.java.classLoader,
        arrayOf(Document::class.java),
    ) { _, _, _ -> throw UnsupportedOperationException("Not used") } as Document
}
