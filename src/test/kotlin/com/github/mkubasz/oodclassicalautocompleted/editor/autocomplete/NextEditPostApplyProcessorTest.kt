package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NextEditPostApplyProcessorTest : BasePlatformTestCase() {

    fun testShortensQualifiedJavaReferencesAndAddsImport() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void test() {
                }
            }
            """.trimIndent(),
        )

        val insertion = "        java.util.ArrayList<String> items = new java.util.ArrayList<>();\n"
        val insertionOffset = myFixture.file.text.indexOf("    }")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(insertionOffset, insertion)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)

        val result = NextEditPostApplyProcessor.applyBestEffort(
            project = project,
            editor = myFixture.editor,
            changedRange = TextRange(insertionOffset, insertionOffset + insertion.length),
        )
        val documentText = myFixture.editor.document.text

        assertTrue(
            "Expected Java reference shortening to modify the document. result=$result text=\n$documentText",
            result.classReferencesShortened,
        )
        assertTrue("Expected import insertion. text=\n$documentText", documentText.contains("import java.util.ArrayList;"))
        assertFalse(documentText.contains("java.util.ArrayList<String>"))
        assertTrue(documentText.contains("ArrayList<String> items = new ArrayList<>();"))
    }

    fun testReformatsChangedJavaRange() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                void test() {
                }
            }
            """.trimIndent(),
        )

        val insertion = "        if(true){\n            System.out.println(\"hi\");\n        }\n"
        val insertionOffset = myFixture.file.text.indexOf("    }")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(insertionOffset, insertion)
        }
        PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)

        val result = NextEditPostApplyProcessor.applyBestEffort(
            project = project,
            editor = myFixture.editor,
            changedRange = TextRange(insertionOffset, insertionOffset + insertion.length),
        )

        assertTrue("Expected formatter to adjust the changed range", result.reformatted)
        assertTrue(myFixture.editor.document.text.contains("if (true) {"))
        assertFalse(myFixture.editor.document.text.contains("if(true){"))
    }
}
