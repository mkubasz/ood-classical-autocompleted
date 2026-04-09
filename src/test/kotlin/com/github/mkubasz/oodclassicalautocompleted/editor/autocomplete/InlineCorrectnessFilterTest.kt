package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InlineCorrectnessFilterTest : BasePlatformTestCase() {

    fun testRejectsPythonCandidateWithBrokenIndentation() {
        val documentText = """
            def run():
                if ready
        """.trimIndent()
        val snapshot = snapshot(
            documentText = documentText,
            caretOffset = documentText.length,
            filePath = "sample.py",
            language = "python",
        )
        val request = ProviderRequest(
            prefix = snapshot.prefix,
            suffix = snapshot.suffix,
            filePath = snapshot.filePath,
            language = snapshot.language,
            cursorOffset = snapshot.caretOffset,
        )

        val result = InlineCorrectnessFilter.check(
            candidate = InlineCompletionCandidate(
                text = ":\nprint('ready')",
                insertionOffset = snapshot.caretOffset,
            ),
            request = request,
            snapshot = snapshot,
        )

        assertTrue(result is InlineCorrectnessFilter.Result.Reject)
        val rejection = result as InlineCorrectnessFilter.Result.Reject
        assertEquals(InlineCorrectnessFilter.LanguageFamily.PYTHON, rejection.family)
        assertEquals(InlineCorrectnessFilter.FailureReason.STRUCTURE, rejection.reason)
    }

    fun testDetectsLanguageFamiliesFromLanguageAndFilePath() {
        assertEquals(
            InlineCorrectnessFilter.LanguageFamily.JVM,
            InlineCorrectnessFilter.LanguageFamily.from("java", "Sample.java"),
        )
        assertEquals(
            InlineCorrectnessFilter.LanguageFamily.PYTHON,
            InlineCorrectnessFilter.LanguageFamily.from("python", "sample.py"),
        )
        assertEquals(
            InlineCorrectnessFilter.LanguageFamily.GO,
            InlineCorrectnessFilter.LanguageFamily.from("go", "main.go"),
        )
    }

    fun testPassesSimpleJavaStatement() {
        myFixture.configureByText(
            "Sample.java",
            """
            class Sample {
                void run() {
                    <caret>
                }
            }
            """.trimIndent(),
        )
        val snapshot = snapshot(
            documentText = myFixture.editor.document.text,
            caretOffset = myFixture.editor.caretModel.offset,
            filePath = myFixture.file.virtualFile.path,
            language = "java",
        )
        val request = ProviderRequest(
            prefix = snapshot.prefix,
            suffix = snapshot.suffix,
            filePath = snapshot.filePath,
            language = snapshot.language,
            cursorOffset = snapshot.caretOffset,
        )

        val result = InlineCorrectnessFilter.check(
            candidate = InlineCompletionCandidate(
                text = "return;",
                insertionOffset = snapshot.caretOffset,
            ),
            request = request,
            snapshot = snapshot,
        )

        assertTrue(result is InlineCorrectnessFilter.Result.Pass)
        assertEquals(InlineCorrectnessFilter.LanguageFamily.JVM, result.family)
    }

    fun testPassesSingleLineKotlinDataClassHeaderContinuation() {
        myFixture.configureByText(
            "Sample.kt",
            "data class MyC",
        )
        val inlineContext = InlineModelContext(
            lexicalContext = InlineLexicalContext.CODE,
            isDefinitionHeaderLikeContext = true,
        )
        val snapshot = snapshot(
            documentText = myFixture.editor.document.text,
            caretOffset = myFixture.editor.document.textLength,
            filePath = myFixture.file.virtualFile.path,
            language = "kotlin",
            inlineContext = inlineContext,
        )
        val request = ProviderRequest(
            prefix = snapshot.prefix,
            suffix = snapshot.suffix,
            filePath = snapshot.filePath,
            language = snapshot.language,
            cursorOffset = snapshot.caretOffset,
            inlineContext = inlineContext,
        )

        val result = InlineCorrectnessFilter.check(
            candidate = InlineCompletionCandidate(
                text = "onfig(",
                insertionOffset = snapshot.caretOffset,
            ),
            request = request,
            snapshot = snapshot,
        )

        assertTrue("result=$result", result is InlineCorrectnessFilter.Result.Pass)
        assertEquals(InlineCorrectnessFilter.LanguageFamily.JVM, result.family)
    }

    private fun snapshot(
        documentText: String,
        caretOffset: Int,
        filePath: String,
        language: String,
        inlineContext: InlineModelContext? = null,
    ): CompletionContextSnapshot = CompletionContextSnapshot(
        filePath = filePath,
        language = language,
        documentText = documentText,
        documentStamp = 0L,
        caretOffset = caretOffset,
        prefix = documentText.substring(0, caretOffset),
        suffix = documentText.substring(caretOffset),
        prefixWindow = documentText.substring(0, caretOffset),
        suffixWindow = documentText.substring(caretOffset),
        inlineContext = inlineContext,
        project = project,
    )
}
