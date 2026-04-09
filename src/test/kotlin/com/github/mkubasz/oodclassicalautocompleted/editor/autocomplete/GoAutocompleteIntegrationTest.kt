package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GoAutocompleteIntegrationTest : BasePlatformTestCase() {

    fun testBuildCapturesReceiverExpressionInGo() {
        myFixture.configureByText(
            "main.go",
            """
            package main

            type DatabaseClient struct{}

            func run() {
                client := DatabaseClient{}
                client.
            }
            """.trimIndent(),
        )
        if (!isGoPsiAvailable()) return

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("client.") + "client.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("client", context?.receiverExpression)
        assertTrue("context=$context", context?.isAfterMemberAccess == true)
    }

    fun testBuildMarksDefinitionHeaderAndParameterListContextInGo() {
        myFixture.configureByText(
            "main.go",
            """
            package main

            func run(client 
            """.trimIndent(),
        )
        if (!isGoPsiAvailable()) return

        val document = myFixture.editor.document
        val context = PsiInlineContextBuilder.build(project, document, document.text, document.textLength)

        assertNotNull(context)
        assertTrue("context=$context", context?.isDefinitionHeaderLikeContext == true)
        assertTrue("context=$context", context?.isInParameterListLikeContext == true)
    }

    fun testAppliesGoImportWhenGoPluginIsAvailable() {
        myFixture.configureByText(
            "main.go",
            """
            package main

            func main() {
            }
            """.trimIndent(),
        )
        if (!isGoPsiAvailable()) return

        val insertion = "\tfmt.Println(\"hi\")\n"
        val insertionOffset = myFixture.file.text.indexOf("}")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(insertionOffset, insertion)
            myFixture.editor.caretModel.moveToOffset(insertionOffset + 1)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        if (!hasImportActionAtOffset(insertionOffset + 1)) return

        val resolved = NextEditImportActionResolver.applyBestEffort(
            project = project,
            editor = myFixture.editor,
            changedRange = TextRange(insertionOffset, insertionOffset + insertion.length),
        )

        assertTrue("Expected import resolution to run for fmt", resolved)
        assertTrue(myFixture.editor.document.text.contains("\"fmt\""))
    }

    private fun hasImportActionAtOffset(offset: Int): Boolean {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document) ?: return false
        myFixture.editor.caretModel.moveToOffset(offset.coerceIn(0, myFixture.editor.document.textLength))
        return ShowIntentionActionsHandler
            .calcCachedIntentions(project, myFixture.editor, psiFile)
            .allActions
            .any { action -> action.text.contains("import", ignoreCase = true) }
    }

    private fun isGoPsiAvailable(): Boolean {
        val language = myFixture.file.language
        return language.id.contains("go", ignoreCase = true) ||
            language.displayName.contains("go", ignoreCase = true)
    }
}
