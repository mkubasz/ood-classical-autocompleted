package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AutocompleteProviderFactoryTest : BasePlatformTestCase() {

    fun testReturnsNullWhenAnthropicKeysBlank() {
        val state = PluginSettings.State().apply {
            apiKey = ""
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC
        }
        assertNull(AutocompleteProviderFactory.createFimProvider(state))
    }

    fun testCreatesAnthropicProvider() {
        val state = PluginSettings.State().apply {
            apiKey = "sk-key"
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC
        }
        val provider = AutocompleteProviderFactory.createFimProvider(state)
        assertNotNull(provider)
        assertTrue(provider is AnthropicAutocompleteProvider)
        provider!!.dispose()
    }

    fun testReturnsNullWhenInceptionLabsKeyBlank() {
        val state = PluginSettings.State().apply {
            inceptionLabsApiKey = ""
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        }
        assertNull(AutocompleteProviderFactory.createFimProvider(state))
        assertNull(AutocompleteProviderFactory.createNextEditProvider(state))
    }

    fun testCreatesFimProviderForInceptionLabs() {
        val state = PluginSettings.State().apply {
            inceptionLabsApiKey = "il-key"
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        }
        val provider = AutocompleteProviderFactory.createFimProvider(state)
        assertNotNull(provider)
        assertTrue(provider is InceptionLabsFimProvider)
        provider!!.dispose()
    }

    fun testCreatesNextEditProviderForInceptionLabs() {
        val state = PluginSettings.State().apply {
            inceptionLabsApiKey = "il-key"
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        }
        val provider = AutocompleteProviderFactory.createNextEditProvider(state)
        assertNotNull(provider)
        assertTrue(provider is InceptionLabsNextEditProvider)
        provider!!.dispose()
    }

    fun testReturnsNoNextEditProviderForAnthropic() {
        val state = PluginSettings.State().apply {
            apiKey = "sk-key"
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC
        }
        assertNull(AutocompleteProviderFactory.createNextEditProvider(state))
    }

    fun testUsesCustomBaseUrlForInceptionLabs() {
        val state = PluginSettings.State().apply {
            inceptionLabsApiKey = "il-key"
            inceptionLabsBaseUrl = "https://custom.endpoint/v1"
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        }
        val fim = AutocompleteProviderFactory.createFimProvider(state)
        val nextEdit = AutocompleteProviderFactory.createNextEditProvider(state)
        assertNotNull(fim)
        assertNotNull(nextEdit)
        fim!!.dispose()
        nextEdit!!.dispose()
    }

    fun testAutocompleteRequestDefaultsOptionalFieldsToNull() {
        val request = AutocompleteRequest(
            prefix = "fun main() {",
            suffix = "}",
            filePath = "Main.kt",
            language = "kt",
        )
        assertNull(request.cursorOffset)
        assertNull(request.inlineContext)
        assertNull(request.recentlyViewedSnippets)
        assertNull(request.editDiffHistory)
    }

    fun testAutocompleteRequestCopyEnrichesWithContext() {
        val base = AutocompleteRequest(
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
            recentlyViewedSnippets = snippets,
            editDiffHistory = diffs,
        )

        assertEquals(8, enriched.cursorOffset)
        assertEquals(InlineLexicalContext.CODE, enriched.inlineContext?.lexicalContext)
        assertEquals(1, enriched.recentlyViewedSnippets?.size)
        assertEquals("Other.kt", enriched.recentlyViewedSnippets?.get(0)?.filePath)
        assertEquals(1, enriched.editDiffHistory?.size)
        assertNull(base.cursorOffset)
        assertNull(base.inlineContext)
    }
}
