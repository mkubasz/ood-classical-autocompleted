package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginSettingsTest : BasePlatformTestCase() {

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

    fun testProviderTypeEnumValues() {
        val types = AutocompleteProviderType.entries
        assertEquals(2, types.size)
        assertTrue(AutocompleteProviderType.ANTHROPIC in types)
        assertTrue(AutocompleteProviderType.INCEPTION_LABS in types)
    }

    fun testDefaultsToAnthropicInlineAndInceptionNextEdit() {
        val state = PluginSettings.State()
        assertEquals(AutocompleteProviderType.ANTHROPIC, state.resolvedInlineProvider())
        assertEquals(PluginSettings.DEFAULT_NEXT_EDIT_PROVIDER, state.resolvedNextEditProvider())
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
        assertFalse(state.gitDiffContextEnabled)
        assertFalse(state.correctnessFilterEnabled)
        assertEquals(0.0, state.minConfidenceScore)
        assertEquals(4_000, state.contextBudgetChars)
        assertFalse(state.lspContextFallbackEnabled)
        assertFalse(state.localRetrievalEnabled)
        assertEquals(3, state.retrievalMaxChunks)
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

    fun testIsConfiguredWhenInlineProviderHasCredentials() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                inlineProvider = AutocompleteProviderType.ANTHROPIC,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            )
        )

        credentials.setApiKey(AutocompleteProviderType.ANTHROPIC, "sk-key")

        assertTrue(settings.isConfigured)
        assertTrue(settings.hasInlineCapabilityConfigured())
        assertEquals(AutocompleteProviderType.ANTHROPIC, settings.activeInlineProvider())
    }

    fun testFallsBackToNextEditProviderForInlineCapability() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                inlineProvider = AutocompleteProviderType.ANTHROPIC,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            )
        )

        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")

        assertTrue(settings.isConfigured)
        assertTrue(settings.hasInlineCapabilityConfigured())
        assertTrue(settings.isNextEditConfigured())
        assertEquals(AutocompleteProviderType.INCEPTION_LABS, settings.activeInlineProvider())
    }

    fun testDoesNotFallBackToNextEditProviderWhenNextEditDisabled() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                inlineProvider = AutocompleteProviderType.ANTHROPIC,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
                nextEditEnabled = false,
            )
        )

        credentials.setApiKey(AutocompleteProviderType.INCEPTION_LABS, "il-key")

        assertFalse(settings.isConfigured)
        assertFalse(settings.hasInlineCapabilityConfigured())
        assertFalse(settings.isNextEditConfigured())
        assertNull(settings.activeInlineProvider())
    }

    fun testIsConfiguredFalseWhenNoCredentialsExist() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                inlineProvider = AutocompleteProviderType.ANTHROPIC,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            )
        )

        assertFalse(settings.isConfigured)
        assertFalse(settings.hasInlineCapabilityConfigured())
        assertFalse(settings.isNextEditConfigured())
        assertNull(settings.activeInlineProvider())
    }

    fun testLoadStateMigratesLegacySecretsOutOfPersistentState() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                apiKey = "sk-key",
                inceptionLabsApiKey = "il-key",
                inlineProvider = AutocompleteProviderType.ANTHROPIC,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
            )
        )

        assertEquals("sk-key", credentials.getApiKey(AutocompleteProviderType.ANTHROPIC))
        assertEquals("il-key", credentials.getApiKey(AutocompleteProviderType.INCEPTION_LABS))
        assertEquals("", settings.state.apiKey)
        assertEquals("", settings.state.inceptionLabsApiKey)
    }

    fun testPersistsAdvancedInceptionLabsFields() {
        val settings = PluginSettings.getInstance()
        settings.loadState(
            PluginSettings.State(
                inlineProvider = AutocompleteProviderType.INCEPTION_LABS,
                nextEditProvider = AutocompleteProviderType.INCEPTION_LABS,
                inceptionLabsFimMaxTokens = 256,
                inceptionLabsFimPresencePenalty = 1.25,
                inceptionLabsFimStopSequences = "\n\nstop-a\nstop-b",
                inceptionLabsFimExtraBodyJson = """{"reasoning_effort":"low"}""",
                inceptionLabsNextEditTemperature = 0.4,
                inceptionLabsNextEditTopP = 0.9,
                inceptionLabsNextEditExtraBodyJson = """{"reasoning_effort":"medium"}""",
                inceptionLabsNextEditLinesAboveCursor = 7,
                inceptionLabsNextEditLinesBelowCursor = 12,
                gitDiffContextEnabled = true,
                correctnessFilterEnabled = true,
                minConfidenceScore = 0.42,
                contextBudgetChars = 2_600,
                lspContextFallbackEnabled = true,
                localRetrievalEnabled = true,
                retrievalMaxChunks = 4,
            )
        )

        val state = settings.state
        assertEquals(AutocompleteProviderType.INCEPTION_LABS, state.resolvedInlineProvider())
        assertEquals(AutocompleteProviderType.INCEPTION_LABS, state.resolvedNextEditProvider())
        assertEquals(256, state.inceptionLabsFimMaxTokens)
        assertEquals(1.25, state.inceptionLabsFimPresencePenalty)
        assertEquals("\n\nstop-a\nstop-b", state.inceptionLabsFimStopSequences)
        assertEquals("""{"reasoning_effort":"low"}""", state.inceptionLabsFimExtraBodyJson)
        assertEquals(0.4, state.inceptionLabsNextEditTemperature)
        assertEquals(0.9, state.inceptionLabsNextEditTopP)
        assertEquals("""{"reasoning_effort":"medium"}""", state.inceptionLabsNextEditExtraBodyJson)
        assertEquals(7, state.inceptionLabsNextEditLinesAboveCursor)
        assertEquals(12, state.inceptionLabsNextEditLinesBelowCursor)
        assertTrue(state.gitDiffContextEnabled)
        assertTrue(state.correctnessFilterEnabled)
        assertEquals(0.42, state.minConfidenceScore)
        assertEquals(2_600, state.contextBudgetChars)
        assertTrue(state.lspContextFallbackEnabled)
        assertTrue(state.localRetrievalEnabled)
        assertEquals(4, state.retrievalMaxChunks)
    }
}
