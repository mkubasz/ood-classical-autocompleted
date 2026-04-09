package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LspInlineContextProviderTest {

    @Test
    fun mergesLspSignalsWithLocalHeuristics() {
        val documentText = """
            class Runner:
                def run(self) -> None:
                    client.
        """.trimIndent()
        val provider = LspInlineContextProvider(
            backend = object : LspSemanticBackend {
                override fun collect(request: InlineContextRequest): LspSemanticSnapshot = LspSemanticSnapshot(
                    enclosingNames = listOf("Runner", "run"),
                    enclosingKinds = listOf("Class", "Function"),
                    receiverMemberNames = listOf("query", "close"),
                    resolvedReferenceName = "DatabaseClient",
                    resolvedFilePath = "/repo/database_client.py",
                    resolvedSnippet = "class DatabaseClient:\n    def query(self): ...",
                )
            }
        )

        val context = provider.build(
            InlineContextRequest(
                project = unusedProject(),
                document = unusedDocument(),
                documentText = documentText,
                caretOffset = documentText.length,
                filePath = "runner.py",
                languageId = "python",
            )
        )

        assertNotNull(context)
        assertTrue(context?.isAfterMemberAccess == true)
        assertEquals("client", context?.receiverExpression)
        assertEquals(listOf("query", "close"), context?.receiverMemberNames)
        assertEquals(listOf("Runner", "run"), context?.enclosingNames)
        assertEquals("DatabaseClient", context?.resolvedReferenceName)
        assertTrue(context?.resolvedSnippet?.contains("class DatabaseClient") == true)
    }

    @Test
    fun returnsNullWhenNoLspSignalExists() {
        val provider = LspInlineContextProvider(
            backend = object : LspSemanticBackend {
                override fun collect(request: InlineContextRequest): LspSemanticSnapshot? = null
            }
        )

        val context = provider.build(
            InlineContextRequest(
                project = unusedProject(),
                document = unusedDocument(),
                documentText = "value",
                caretOffset = 5,
                filePath = "runner.py",
                languageId = "python",
            )
        )

        assertNull(context)
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
