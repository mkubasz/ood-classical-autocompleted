package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PackedContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineMode
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PreprocessingFacts
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.intellij.mock.MockProjectEx
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelProviderCoordinatorTest {
    private val testRootDisposable = Disposer.newDisposable()
    private val project = MockProjectEx(testRootDisposable)

    @After
    fun tearDown() {
        Disposer.dispose(testRootDisposable)
    }

    @Test
    fun testReturnsPrimaryInlineResultWhenOnlyPrimaryConfigured() = runBlocking {
        val call = call("val x = ")
        val primary = FakeModelProvider(
            id = "primary",
            artifact = CompletionArtifact(
                inlineCandidates = listOf(
                    InlineCompletionCandidate(text = "answer", insertionOffset = call.request.snapshot.caretOffset)
                ),
                sourceName = "primary",
            ),
        )

        val result = ModelProviderCoordinator.completeInline(call, primary, fallback = null)

        assertEquals("primary", result?.sourceName)
        assertEquals(listOf("answer"), result?.inlineCandidates?.map { it.text })
    }

    @Test
    fun testReturnsNullWhenPrimaryHasNoInlineCandidates() = runBlocking {
        val call = call("val x = ")
        val primary = FakeModelProvider(
            id = "primary",
            artifact = CompletionArtifact(sourceName = "fallback"),
        )

        val result = ModelProviderCoordinator.completeInline(call, primary, fallback = null)

        assertNull(result)
    }

    @Test
    fun testReturnsNextEditArtifactWhenConfiguredProviderResponds() = runBlocking {
        val call = call("fun main() {\n}")
        val provider = FakeModelProvider(
            id = "next-edit",
            artifact = CompletionArtifact(
                nextEditCandidates = emptyList(),
                sourceName = "next-edit",
            ),
        )

        val result = ModelProviderCoordinator.completeNextEdit(call, provider)

        assertEquals("next-edit", result?.sourceName)
    }

    private fun call(text: String): ModelCall {
        val document = DocumentImpl(text)
        return ModelCall(
            providerId = "test",
            request = PipelineRequest(
                mode = PipelineMode.INLINE,
                snapshot = EditorSnapshot(
                    project = project,
                    document = document,
                    filePath = "/tmp/Sample.kt",
                    languageId = "kotlin",
                    documentText = text,
                    documentVersion = 1L,
                    caretOffset = text.length,
                    prefix = text,
                    suffix = "",
                    prefixWindow = text,
                    suffixWindow = "",
                    requestId = 1L,
                    isTerminal = false,
                ),
                preprocessingFacts = PreprocessingFacts(),
                packedContext = PackedContext(
                    blocks = emptyList(),
                    summary = "",
                    totalChars = 0,
                    budgetChars = 0,
                ),
            ),
        )
    }

    private class FakeModelProvider(
        override val id: String,
        private val artifact: CompletionArtifact?,
    ) : ModelProvider {
        override val supportsInline: Boolean = true
        override val supportsNextEdit: Boolean = true

        override suspend fun complete(call: ModelCall): CompletionArtifact? = artifact

        override suspend fun completeStreaming(call: ModelCall): Flow<String>? = null

        override fun dispose() = Unit
    }
}
