package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CodeSnippet
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunk
import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.github.mkubasz.oodclassicalautocompleted.settings.ProviderCredentialsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NativeModelProviderFactoryTest : BasePlatformTestCase() {
    // Touching this file keeps the test bytecode aligned with PluginSettings.State defaults.

    private val credentials: ProviderCredentialsService
        get() = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)

    override fun tearDown() {
        try {
            credentials.setApiKey(AutocompleteProviderType.ANTHROPIC, null)
            credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, null)
        } finally {
            super.tearDown()
        }
    }

    fun testReturnsNullWhenAnthropicKeysBlank() {
        val state = PluginSettings.State(
            inlineProvider = AutocompleteProviderType.ANTHROPIC,
        )
        assertNull(NativeModelProviderFactory.createInlineProvider(state))
    }

    fun testCreatesAnthropicProvider() {
        credentials.setApiKey(AutocompleteProviderType.ANTHROPIC, "sk-key")
        val state = PluginSettings.State(
            inlineProvider = AutocompleteProviderType.ANTHROPIC,
        )
        val provider = NativeModelProviderFactory.createInlineProvider(state)
        assertNotNull(provider)
        assertTrue(provider is AnthropicModelProvider)
        provider!!.dispose()
    }

    fun testReturnsNullWhenInceptionLabsKeyBlank() {
        val state = PluginSettings.State(
            inlineProvider = AutocompleteProviderType.INCEPTION_LABS,
            nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
        )
        assertNull(NativeModelProviderFactory.createInlineProvider(state))
        assertNull(NativeModelProviderFactory.createNextEditProvider(state))
    }

    fun testCreatesFimProviderForInceptionLabs() {
        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")
        val state = PluginSettings.State(
            inlineProvider = AutocompleteProviderType.INCEPTION_LABS,
        )
        val provider = NativeModelProviderFactory.createInlineProvider(state)
        assertNotNull(provider)
        assertTrue(provider is InceptionLabsFimModelProvider)
        provider!!.dispose()
    }

    fun testCreatesNextEditProviderForInceptionLabs() {
        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")
        val state = PluginSettings.State(
            nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
        )
        val provider = NativeModelProviderFactory.createNextEditProvider(state)
        assertNotNull(provider)
        assertTrue(provider is InceptionLabsNextEditModelProvider)
        provider!!.dispose()
    }

    fun testReturnsNoNextEditProviderForAnthropic() {
        credentials.setApiKey(AutocompleteProviderType.ANTHROPIC, "sk-key")
        val state = PluginSettings.State(
            nextEditProvider = AutocompleteProviderType.ANTHROPIC,
        )
        assertNull(NativeModelProviderFactory.createNextEditProvider(state))
    }

    fun testReturnsNoNextEditProviderWhenNextEditDisabled() {
        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")
        val state = PluginSettings.State(
            nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            nextEditEnabled = false,
        )
        assertNull(NativeModelProviderFactory.createNextEditProvider(state))
    }

    fun testUsesCustomBaseUrlForInceptionLabs() {
        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")
        val state = PluginSettings.State(
            inlineProvider = AutocompleteProviderType.INCEPTION_LABS,
            nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            inceptionLabsBaseUrl = "https://custom.endpoint/v1",
        )
        val fim = NativeModelProviderFactory.createInlineProvider(state)
        val nextEdit = NativeModelProviderFactory.createNextEditProvider(state)
        assertNotNull(fim)
        assertNotNull(nextEdit)
        fim!!.dispose()
        nextEdit!!.dispose()
    }

    fun testProviderRequestDefaultsOptionalFieldsToNull() {
        val request = ProviderRequest(
            prefix = "fun main() {",
            suffix = "}",
            filePath = "Main.kt",
            language = "kt",
        )
        assertNull(request.cursorOffset)
        assertNull(request.inlineContext)
        assertNull(request.retrievedChunks)
        assertNull(request.recentlyViewedSnippets)
        assertNull(request.editDiffHistory)
        assertNull(request.gitDiff)
    }

    fun testProviderRequestCopyEnrichesWithContext() {
        val base = ProviderRequest(
            prefix = "val x = ",
            suffix = "\n",
            filePath = "Test.kt",
            language = "kt",
        )
        val snippets = listOf(CodeSnippet("Other.kt", "fun other() = 1"))
        val diffs = listOf("--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new")

        val enriched = base.copy(
            cursorOffset = 8,
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isAfterMemberAccess = true,
            ),
            retrievedChunks = listOf(
                RetrievedContextChunk(
                    filePath = "DatabaseClient.kt",
                    content = "class DatabaseClient",
                    score = 2.0,
                )
            ),
            recentlyViewedSnippets = snippets,
            editDiffHistory = diffs,
            gitDiff = "diff --git a/Test.kt b/Test.kt",
        )

        assertEquals(8, enriched.cursorOffset)
        assertEquals(InlineLexicalContext.CODE, enriched.inlineContext?.lexicalContext)
        assertEquals(1, enriched.retrievedChunks?.size)
        assertEquals(1, enriched.recentlyViewedSnippets?.size)
        assertEquals("Other.kt", enriched.recentlyViewedSnippets?.get(0)?.filePath)
        assertEquals(1, enriched.editDiffHistory?.size)
        assertEquals("diff --git a/Test.kt b/Test.kt", enriched.gitDiff)
        assertNull(base.cursorOffset)
        assertNull(base.inlineContext)
        assertNull(base.retrievedChunks)
        assertNull(base.gitDiff)
    }
}
