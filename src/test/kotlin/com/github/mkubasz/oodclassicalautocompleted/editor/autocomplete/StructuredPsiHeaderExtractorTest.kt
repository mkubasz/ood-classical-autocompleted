package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class StructuredPsiHeaderExtractorTest : BasePlatformTestCase() {

    fun testExtractUsesPsiForJavaStructuredHeaders() {
        val headers = StructuredPsiHeaderExtractor.extract(
            project = project,
            text = """
                class Foo {
                    void run() {
                    }
                }
            """.trimIndent(),
            filePath = "Foo.java",
        )

        assertTrue("headers=$headers", headers.contains("class Foo {"))
        assertTrue("headers=$headers", headers.contains("void run() {"))
    }

    fun testExtractUsesPsiForPythonDecoratorsAndDefinitionsWhenAvailable() {
        myFixture.configureByText("agent.py", "class Agent:\n    pass\n")
        if (!isPythonPsiAvailable()) return

        val headers = StructuredPsiHeaderExtractor.extract(
            project = project,
            text = """
                class Agent:
                    @property
                    def history(self) -> History:
                        return self._history
            """.trimIndent(),
            filePath = "agent.py",
        )

        assertTrue("headers=$headers", headers.contains("class Agent:"))
        assertTrue("headers=$headers", headers.contains("@property"))
        assertTrue("headers=$headers", headers.contains("def history(self) -> History:"))
    }

    private fun isPythonPsiAvailable(): Boolean {
        val language = myFixture.file.language
        return language.id.contains("python", ignoreCase = true) ||
            language.displayName.contains("python", ignoreCase = true)
    }
}
