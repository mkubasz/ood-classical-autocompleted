package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiInlineContextBuilderTest : BasePlatformTestCase() {

    fun testBuildCapturesThisReceiverMembersInJava() {
        myFixture.configureByText(
            "Foo.java",
            """
            class Foo {
                private int value;

                void clearHistory() {}

                void run() {
                    this.
                }
            }
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("this.") + "this.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("this", context?.receiverExpression)
        assertTrue(context?.isAfterMemberAccess == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("value") == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("clearHistory") == true)
    }

    fun testBuildInfersReceiverMembersFromResolvedVariableTypeInJava() {
        myFixture.configureByText(
            "DatabaseClient.java",
            """
            class DatabaseClient {
                void query() {}
                void close() {}
            }

            class Runner {
                void run() {
                    DatabaseClient client = new DatabaseClient();
                    client.
                }
            }
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("client.") + "client.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("client", context?.receiverExpression)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("query") == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("close") == true)
    }

    fun testBuildCapturesReceiverExpressionFromPlainTextContext() {
        myFixture.configureByText(
            "Context.txt",
            """
            logger = make_logger()
            logger.
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("logger.") + "logger.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("logger", context?.receiverExpression)
        assertTrue(context?.isAfterMemberAccess == true)
    }

    fun testBuildMarksDefinitionHeaderAndParameterListContextFromPlainText() {
        myFixture.configureByText(
            "Context.txt",
            """
            class Agent:
                def clear_history(self(
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val caretOffset = document.text.length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertTrue(context?.isDefinitionHeaderLikeContext == true)
        assertTrue(context?.isInParameterListLikeContext == true)
    }

    fun testBuildCapturesClassBaseListContextAndMatchingTypesFromPlainText() {
        myFixture.configureByText(
            "Context.txt",
            """
            class Czlowiek:
                pass

            class Bartek(Czlo
            """.trimIndent(),
        )

        val document = myFixture.editor.document
        val caretOffset = document.text.length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertTrue("context=$context", context?.isClassBaseListLikeContext == true)
        assertTrue("context=$context", context?.isDefinitionHeaderLikeContext == true)
        assertTrue("context=$context", context?.isInParameterListLikeContext == true)
        assertEquals("Czlo", context?.classBaseReferencePrefix)
        assertTrue("context=$context", context?.matchingTypeNames?.contains("Czlowiek") == true)
    }

    fun testBuildCapturesSelfReceiverMembersInPython() {
        myFixture.configureByText(
            "agent.py",
            """
            class Agent:
                def __init__(self) -> None:
                    self._history = []

                @property
                def history(self) -> list[str]:
                    return self._history

                def clear_history(self) -> None:
                    self.
            """.trimIndent(),
        )

        if (!isPythonPsiAvailable()) return

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("self.") + "self.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("self", context?.receiverExpression)
        assertTrue(context?.isAfterMemberAccess == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("history") == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("clear_history") == true)
    }

    fun testBuildInfersReceiverMembersFromResolvedVariableTypeInPython() {
        myFixture.configureByText(
            "runner.py",
            """
            class DatabaseClient:
                def query(self) -> None:
                    pass

                def close(self) -> None:
                    pass

            class Runner:
                def run(self) -> None:
                    client: DatabaseClient = DatabaseClient()
                    client.
            """.trimIndent(),
        )

        if (!isPythonPsiAvailable()) return

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("client.") + "client.".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertEquals("client", context?.receiverExpression)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("query") == true)
        assertTrue("context=$context", context?.receiverMemberNames?.contains("close") == true)
    }

    fun testBuildMarksSetterDefinitionHeaderAndParameterListContextInPython() {
        myFixture.configureByText(
            "setter.py",
            """
            class Agent:
                @property
                def history(self) -> list[str]:
                    return []

                @history.setter
                def history(self
            """.trimIndent(),
        )

        if (!isPythonPsiAvailable()) return

        val document = myFixture.editor.document
        val caretOffset = document.text.length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertTrue("context=$context", context?.isDefinitionHeaderLikeContext == true)
        assertTrue("context=$context", context?.isInParameterListLikeContext == true)
    }

    fun testBuildMarksDecoratorContextInPython() {
        myFixture.configureByText(
            "decorator.py",
            """
            class Agent:
                @property
                def history(self) -> list[str]:
                    return []
            """.trimIndent(),
        )

        if (!isPythonPsiAvailable()) return

        val document = myFixture.editor.document
        val caretOffset = document.text.indexOf("@property") + "@property".length
        val context = PsiInlineContextBuilder.build(project, document, document.text, caretOffset)

        assertNotNull(context)
        assertTrue("context=$context", context?.isDecoratorLikeContext == true)
    }

    private fun isPythonPsiAvailable(): Boolean {
        val language = myFixture.file.language
        return language.id.contains("python", ignoreCase = true) ||
            language.displayName.contains("python", ignoreCase = true)
    }
}
