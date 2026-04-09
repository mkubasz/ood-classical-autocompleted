package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.RetrievedContextChunkKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class WorkspaceRetrievalServiceTest : BasePlatformTestCase() {

    fun testRetrievesRelevantChunkFromOtherProjectFile() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def run(self, client):
                    client.query_users()
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/database_client.py",
            """
            class DatabaseClient:
                def query_users(self):
                    return []
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                receiverExpression = "client",
                resolvedReferenceName = "DatabaseClient",
            ),
            project = project,
        )

        val result = project.getService(WorkspaceRetrievalService::class.java).retrieve(snapshot, maxChunks = 3)
        val chunks = result.chunks

        assertTrue("chunks=$chunks", chunks.any { it.filePath.endsWith("database_client.py") })
        assertTrue("chunks=$chunks", chunks.any { it.content.contains("query_users") })
        assertTrue("chunks=$chunks", chunks.any { it.chunkKind == RetrievedContextChunkKind.SYMBOL })
        assertFalse("Expected the first retrieval not to come from cache", result.fromCache)
    }

    fun testDoesNotRetrieveCurrentFileAsWorkspaceContext() {
        myFixture.configureByText(
            "runner.py",
            """
            def calculate_average(values):
                return sum(values) / len(values)
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/math_helpers.py",
            """
            def calculate_average(values):
                return statistics.mean(values)
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                currentDefinitionName = "calculate_average",
            ),
            project = project,
        )

        val chunks = project.getService(WorkspaceRetrievalService::class.java).retrieve(snapshot, maxChunks = 3).chunks

        assertTrue("chunks=$chunks", chunks.none { it.filePath == myFixture.file.virtualFile.path })
        assertTrue("chunks=$chunks", chunks.any { it.filePath.endsWith("math_helpers.py") })
    }

    fun testReportsCacheHitForRepeatedRetrieval() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def run(self, client):
                    return client.query_users()
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/database_client.py",
            """
            class DatabaseClient:
                def query_users(self):
                    return []
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                resolvedReferenceName = "DatabaseClient",
            ),
            project = project,
        )

        val service = project.getService(WorkspaceRetrievalService::class.java)
        val first = service.retrieve(snapshot, maxChunks = 3)
        val second = service.retrieve(snapshot, maxChunks = 3)

        assertTrue(second.fromCache)
        assertEquals(first.chunks, second.chunks)
        assertTrue(second.queryTermCount > 0)
    }

    fun testRetrievalUsesSymbolTokensToMatchSnakeCasePaths() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def run(self, client):
                    client.
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/database_client_helpers.py",
            """
            def open_connection():
                return None
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                receiverExpression = "client",
                resolvedReferenceName = "DatabaseClient",
            ),
            project = project,
        )

        val chunks = project.getService(WorkspaceRetrievalService::class.java).retrieve(snapshot, maxChunks = 3).chunks

        assertTrue("chunks=$chunks", chunks.any { it.filePath.endsWith("database_client_helpers.py") })
    }

    fun testRetrievalPrefersFileDiversityWhenMultipleFilesMatch() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def run(self, client):
                    return client.query_users()
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/database_client.py",
            """
            class DatabaseClient:
                def query_users(self):
                    return []


            class DatabaseArchiveClient:
                def query_users_archive(self):
                    return []


            class DatabaseSyncClient:
                def query_users_sync(self):
                    return []
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/secondary_client.py",
            """
            class SecondaryClient:
                def query_users(self):
                    return []
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                receiverExpression = "client",
                resolvedReferenceName = "DatabaseClient",
            ),
            project = project,
        )

        val chunks = project.getService(WorkspaceRetrievalService::class.java).retrieve(snapshot, maxChunks = 2).chunks

        assertEquals(2, chunks.size)
        assertEquals(2, chunks.map { it.filePath }.distinct().size)
        assertTrue("chunks=$chunks", chunks.any { it.filePath.endsWith("database_client.py") })
        assertTrue("chunks=$chunks", chunks.any { it.filePath.endsWith("secondary_client.py") })
    }

    fun testRetrievalPrefersSymbolShapedChunkOverFallbackWindow() {
        myFixture.configureByText(
            "runner.py",
            """
            class Runner:
                def run(self, client):
                    return client.query_users()
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "services/database_client.py",
            """
            class DatabaseClient:
                def query_users(self):
                    return []

                def query_accounts(self):
                    return []
            """.trimIndent(),
        )

        val snapshot = CompletionContextSnapshot(
            filePath = myFixture.file.virtualFile.path,
            language = "python",
            documentText = myFixture.editor.document.text,
            documentStamp = myFixture.editor.document.modificationStamp,
            caretOffset = myFixture.editor.document.textLength,
            prefix = myFixture.editor.document.text,
            suffix = "",
            prefixWindow = myFixture.editor.document.text,
            suffixWindow = "",
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                resolvedReferenceName = "DatabaseClient",
            ),
            project = project,
        )

        val bestChunk = project.getService(WorkspaceRetrievalService::class.java)
            .retrieve(snapshot, maxChunks = 1)
            .chunks
            .single()

        assertEquals(RetrievedContextChunkKind.SYMBOL, bestChunk.chunkKind)
        assertTrue(bestChunk.content.startsWith("class DatabaseClient:"))
    }
}
