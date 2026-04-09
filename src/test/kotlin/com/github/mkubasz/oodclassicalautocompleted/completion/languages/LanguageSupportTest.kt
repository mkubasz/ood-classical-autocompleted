package com.github.mkubasz.oodclassicalautocompleted.completion.languages

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.go.GoLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.java.JavaLanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.python.PythonLanguageSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class LanguageSupportTest : BasePlatformTestCase() {

    fun testPythonLanguageSupportExtractsScopedDefinitionContext() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def execute(self, client, user_id):
                    client.<caret>
            """.trimIndent(),
        )

        val context = PythonLanguageSupport.resolveInlineContext(snapshot("python")).context

        assertNotNull(context)
        assertEquals("execute", context?.currentDefinitionName)
        assertEquals(listOf("client", "user_id"), context?.currentParameterNames)
        assertEquals(listOf("Function", "Class"), context?.enclosingKinds)
        assertTrue(context?.isAfterMemberAccess == true)
    }

    fun testJavaLanguageSupportExtractsMethodParametersAndEnclosingKinds() {
        myFixture.configureByText(
            "Runner.java",
            """
            class Runner {
                void execute(ServiceClient client, RequestContext context) {
                    client.<caret>
                }
            }
            """.trimIndent(),
        )

        val resolved = JavaLanguageSupport.resolveInlineContext(snapshot("java")).context

        assertNotNull(resolved)
        assertEquals("execute", resolved?.currentDefinitionName)
        assertEquals(listOf("client", "context"), resolved?.currentParameterNames)
        assertEquals(listOf("Method", "Class"), resolved?.enclosingKinds)
        assertTrue(resolved?.isAfterMemberAccess == true)
    }

    fun testGoLanguageSupportExtractsReceiverAwareFunctionContext() {
        myFixture.configureByText(
            "main.go",
            """
            type Client struct{}

            func (c *Client) QueryUsers(ctx context.Context, userID string) error {
                c.<caret>
            }
            """.trimIndent(),
        )

        val resolved = GoLanguageSupport.resolveInlineContext(snapshot("go")).context

        assertNotNull(resolved)
        assertEquals("QueryUsers", resolved?.currentDefinitionName)
        assertEquals(listOf("ctx", "userID"), resolved?.currentParameterNames)
        assertTrue("kinds=${resolved?.enclosingKinds}", resolved?.enclosingKinds?.contains("Function") == true)
        assertEquals("c", resolved?.receiverExpression)
        assertTrue(resolved?.isAfterMemberAccess == true)
    }

    private fun snapshot(languageId: String): EditorSnapshot {
        val text = myFixture.editor.document.text
        val caretOffset = myFixture.editor.caretModel.offset
        return EditorSnapshot(
            project = project,
            document = myFixture.editor.document,
            filePath = myFixture.file.virtualFile.path,
            languageId = languageId,
            documentText = text,
            documentVersion = myFixture.editor.document.modificationStamp,
            caretOffset = caretOffset,
            prefix = text.substring(0, caretOffset),
            suffix = text.substring(caretOffset),
            prefixWindow = text.substring(0, caretOffset),
            suffixWindow = text.substring(caretOffset),
            requestId = 1L,
            isTerminal = false,
        )
    }
}
