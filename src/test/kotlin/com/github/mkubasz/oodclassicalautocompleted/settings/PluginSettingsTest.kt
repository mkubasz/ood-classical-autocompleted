package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginSettingsTest : BasePlatformTestCase() {

    fun testProviderTypeEnumValues() {
        val types = AutocompleteProviderType.entries
        assertEquals(2, types.size)
        assertTrue(AutocompleteProviderType.ANTHROPIC in types)
        assertTrue(AutocompleteProviderType.INCEPTION_LABS in types)
    }

    fun testDefaultsToAnthropic() {
        val state = PluginSettings.State()
        assertEquals(AutocompleteProviderType.ANTHROPIC, state.autocompleteProvider)
        assertTrue(state.autocompleteEnabled)
        assertEquals(300L, state.debounceMs)
    }

    fun testInceptionLabsFieldsDefaultBlank() {
        val state = PluginSettings.State()
        assertEquals("", state.inceptionLabsApiKey)
        assertEquals("", state.inceptionLabsBaseUrl)
        assertEquals("", state.inceptionLabsModel)
    }

    fun testAnthropicFieldsDefaultToAutocompleteDefaults() {
        val state = PluginSettings.State()
        assertEquals("", state.apiKey)
        assertEquals(PluginSettings.DEFAULT_API_URL, state.baseUrl)
        assertEquals(PluginSettings.DEFAULT_MODEL, state.model)
    }

    fun testIsConfiguredAnthropicWithApiKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State(
            apiKey = "sk-key",
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC,
        ))
        assertTrue(settings.isConfigured)
    }

    fun testIsConfiguredAnthropicWithoutApiKeyIsFalse() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State(
            apiKey = "",
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC,
        ))
        assertFalse(settings.isConfigured)
    }

    fun testIsConfiguredInceptionLabsWithKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State(
            inceptionLabsApiKey = "il-key",
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS,
        ))
        assertTrue(settings.isConfigured)
    }

    fun testIsConfiguredInceptionLabsNoKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State(
            inceptionLabsApiKey = "",
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS,
        ))
        assertFalse(settings.isConfigured)
    }
}
