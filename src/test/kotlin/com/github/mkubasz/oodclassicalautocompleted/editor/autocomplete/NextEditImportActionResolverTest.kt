package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NextEditImportActionResolverTest : BasePlatformTestCase() {
    // Uses PluginSettings.State.copy() and needs to stay in sync with settings defaults.

    fun testAppliesJavaImportQuickFixForInsertedTypeReference() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void test() {
                }
            }
            """.trimIndent(),
        )

        val insertion = "        ArrayList<String> items = new ArrayList<>();\n"
        val insertionOffset = myFixture.file.text.indexOf("    }")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(insertionOffset, insertion)
            myFixture.editor.caretModel.moveToOffset(insertionOffset + "        ".length)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        if (!hasImportActionAtOffset(insertionOffset + "        ".length)) return

        val resolved = NextEditImportActionResolver.applyBestEffort(
            project = project,
            editor = myFixture.editor,
            changedRange = TextRange(insertionOffset, insertionOffset + insertion.length),
        )

        assertTrue("Expected import resolution to run for ArrayList", resolved)
        assertTrue(myFixture.editor.document.text.contains("import java.util.ArrayList;"))
    }

    fun testSkipsImportResolutionWhenSettingDisabled() {
        val settings = PluginSettings.getInstance()
        val originalState = settings.state.copy()
        settings.loadState(originalState.copy(nextEditResolveImports = false))

        try {
            myFixture.configureByText(
                "Foo.java",
                """
                class Foo {
                    void test() {
                    }
                }
                """.trimIndent(),
            )

            val insertion = "        ArrayList<String> items = new ArrayList<>();\n"
            val insertionOffset = myFixture.file.text.indexOf("    }")
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(insertionOffset, insertion)
                myFixture.editor.caretModel.moveToOffset(insertionOffset + "        ".length)
            }
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)

            val resolved = NextEditImportActionResolver.applyBestEffort(
                project = project,
                editor = myFixture.editor,
                changedRange = TextRange(insertionOffset, insertionOffset + insertion.length),
            )

            assertFalse("Import resolution should be skipped when disabled", resolved)
            assertFalse(myFixture.editor.document.text.contains("import java.util.ArrayList;"))
        } finally {
            settings.loadState(originalState)
        }
    }

    private fun hasImportActionAtOffset(offset: Int): Boolean {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(myFixture.editor.document) ?: return false
        myFixture.editor.caretModel.moveToOffset(offset.coerceIn(0, myFixture.editor.document.textLength))
        return ShowIntentionActionsHandler
            .calcCachedIntentions(project, myFixture.editor, psiFile)
            .allActions
            .any { action -> action.text.contains("import", ignoreCase = true) }
    }

}
