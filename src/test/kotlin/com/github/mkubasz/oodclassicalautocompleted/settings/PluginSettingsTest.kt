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
        assertEquals(150L, state.tabAcceptMinDwellMs)
        assertTrue(state.acceptOnRightArrow)
        assertTrue(state.acceptOnEndKey)
        assertEquals("", state.cycleNextShortcut)
        assertEquals("", state.cyclePreviousShortcut)
        assertTrue(state.nextEditEnabled)
        assertTrue(state.nextEditResolveImports)
        assertEquals(20, state.nextEditPreviewMaxLines)
        assertEquals(15_000L, state.suggestionCacheTtlMs)
        assertEquals(32, state.suggestionCacheMaxEntries)
        assertFalse(state.debugMetricsLogging)
    }

    fun testInceptionLabsFieldsDefaultBlank() {
        val state = PluginSettings.State()
        assertEquals("", state.inceptionLabsApiKey)
        assertEquals("", state.inceptionLabsBaseUrl)
        assertEquals("", state.inceptionLabsModel)
        assertNull(state.inceptionLabsFimMaxTokens)
        assertNull(state.inceptionLabsFimPresencePenalty)
        assertNull(state.inceptionLabsFimTemperature)
        assertNull(state.inceptionLabsFimTopP)
        assertEquals("", state.inceptionLabsFimStopSequences)
        assertEquals("", state.inceptionLabsFimExtraBodyJson)
        assertNull(state.inceptionLabsNextEditMaxTokens)
        assertNull(state.inceptionLabsNextEditPresencePenalty)
        assertNull(state.inceptionLabsNextEditTemperature)
        assertNull(state.inceptionLabsNextEditTopP)
        assertEquals("", state.inceptionLabsNextEditStopSequences)
        assertEquals("", state.inceptionLabsNextEditExtraBodyJson)
        assertEquals(5, state.inceptionLabsNextEditLinesAboveCursor)
        assertEquals(10, state.inceptionLabsNextEditLinesBelowCursor)
    }

    fun testAnthropicFieldsDefaultToAutocompleteDefaults() {
        val state = PluginSettings.State()
        assertEquals("", state.apiKey)
        assertEquals(PluginSettings.DEFAULT_API_URL, state.baseUrl)
        assertEquals(PluginSettings.DEFAULT_MODEL, state.model)
    }

    fun testIsConfiguredAnthropicWithApiKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State().apply {
            apiKey = "sk-key"
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC
        })
        assertTrue(settings.isConfigured)
    }

    fun testIsConfiguredAnthropicWithoutApiKeyIsFalse() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State().apply {
            apiKey = ""
            autocompleteProvider = AutocompleteProviderType.ANTHROPIC
        })
        assertFalse(settings.isConfigured)
    }

    fun testIsConfiguredInceptionLabsWithKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State().apply {
            inceptionLabsApiKey = "il-key"
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        })
        assertTrue(settings.isConfigured)
    }

    fun testIsConfiguredInceptionLabsNoKey() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State().apply {
            inceptionLabsApiKey = ""
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
        })
        assertFalse(settings.isConfigured)
    }

    fun testPersistsAdvancedInceptionLabsFields() {
        val settings = PluginSettings.getInstance()
        settings.loadState(PluginSettings.State().apply {
            inceptionLabsApiKey = "il-key"
            autocompleteProvider = AutocompleteProviderType.INCEPTION_LABS
            inceptionLabsFimMaxTokens = 256
            inceptionLabsFimPresencePenalty = 1.25
            inceptionLabsFimStopSequences = "\n\nstop-a\nstop-b"
            inceptionLabsFimExtraBodyJson = """{"reasoning_effort":"low"}"""
            inceptionLabsNextEditTemperature = 0.4
            inceptionLabsNextEditTopP = 0.9
            inceptionLabsNextEditExtraBodyJson = """{"reasoning_effort":"medium"}"""
            inceptionLabsNextEditLinesAboveCursor = 7
            inceptionLabsNextEditLinesBelowCursor = 12
        })

        val state = settings.state
        assertEquals(256, state.inceptionLabsFimMaxTokens)
        assertEquals(1.25, state.inceptionLabsFimPresencePenalty)
        assertEquals("\n\nstop-a\nstop-b", state.inceptionLabsFimStopSequences)
        assertEquals("""{"reasoning_effort":"low"}""", state.inceptionLabsFimExtraBodyJson)
        assertEquals(0.4, state.inceptionLabsNextEditTemperature)
        assertEquals(0.9, state.inceptionLabsNextEditTopP)
        assertEquals("""{"reasoning_effort":"medium"}""", state.inceptionLabsNextEditExtraBodyJson)
        assertEquals(7, state.inceptionLabsNextEditLinesAboveCursor)
        assertEquals(12, state.inceptionLabsNextEditLinesBelowCursor)
    }
}
