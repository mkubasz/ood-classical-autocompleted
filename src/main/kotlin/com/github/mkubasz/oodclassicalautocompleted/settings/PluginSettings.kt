package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class AutocompleteProviderType {
    ANTHROPIC,
    INCEPTION_LABS,
}

@State(
    name = "com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings",
    storages = [Storage("OodSettings.xml")]
)
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        // Legacy secret fields retained only to migrate persisted keys into Password Safe.
        var apiKey: String = "",
        var baseUrl: String = DEFAULT_API_URL,
        var model: String = DEFAULT_MODEL,
        // Autocompletion general
        var autocompleteEnabled: Boolean = true,
        var autocompleteProvider: AutocompleteProviderType = AutocompleteProviderType.ANTHROPIC,
        var inlineProvider: AutocompleteProviderType? = null,
        var nextEditProvider: AutocompleteProviderType? = null,
        var debounceMs: Long = 300L,
        var tabAcceptMinDwellMs: Long = 150L,
        var acceptOnRightArrow: Boolean = true,
        var acceptOnEndKey: Boolean = true,
        var cycleNextShortcut: String = "",
        var cyclePreviousShortcut: String = "",
        var nextEditEnabled: Boolean = true,
        var nextEditResolveImports: Boolean = true,
        var nextEditPreviewMaxLines: Int = 20,
        var suggestionCacheTtlMs: Long = 15_000L,
        var suggestionCacheMaxEntries: Int = 32,
        var debugMetricsLogging: Boolean = false,
        var terminalCompletionEnabled: Boolean = false,
        var terminalProvider: AutocompleteProviderType = AutocompleteProviderType.ANTHROPIC,
        var gitDiffContextEnabled: Boolean = false,
        var correctnessFilterEnabled: Boolean = false,
        var minConfidenceScore: Double = 0.0,
        var contextBudgetChars: Int = 4_000,
        var lspContextFallbackEnabled: Boolean = false,
        var localRetrievalEnabled: Boolean = false,
        var retrievalMaxChunks: Int = 3,
        // Inception Labs autocomplete
        var inceptionLabsApiKey: String = "",
        var inceptionLabsBaseUrl: String = "",
        var inceptionLabsModel: String = "",
        var inceptionLabsFimMaxTokens: Int? = null,
        var inceptionLabsFimPresencePenalty: Double? = null,
        var inceptionLabsFimTemperature: Double? = null,
        var inceptionLabsFimTopP: Double? = null,
        var inceptionLabsFimStopSequences: String = "",
        var inceptionLabsFimExtraBodyJson: String = "",
        var inceptionLabsNextEditMaxTokens: Int? = null,
        var inceptionLabsNextEditPresencePenalty: Double? = null,
        var inceptionLabsNextEditTemperature: Double? = null,
        var inceptionLabsNextEditTopP: Double? = null,
        var inceptionLabsNextEditStopSequences: String = "",
        var inceptionLabsNextEditExtraBodyJson: String = "",
        var inceptionLabsNextEditLinesAboveCursor: Int = 5,
        var inceptionLabsNextEditLinesBelowCursor: Int = 10,
        var inceptionLabsNextEditDiffusing: Boolean = false,
        var inceptionLabsNextEditReasoningEffort: String = "low",
    ) {
        fun resolvedInlineProvider(): AutocompleteProviderType = inlineProvider ?: autocompleteProvider

        fun resolvedNextEditProvider(): AutocompleteProviderType = nextEditProvider ?: DEFAULT_NEXT_EDIT_PROVIDER

        fun sanitized(): State {
            val resolvedInline = resolvedInlineProvider()
            return copy(
                apiKey = "",
                inceptionLabsApiKey = "",
                autocompleteProvider = resolvedInline,
                inlineProvider = resolvedInline,
                nextEditProvider = resolvedNextEditProvider(),
            )
        }
    }

    private var myState = State()

    override fun getState(): State = myState.sanitized()

    override fun loadState(state: State) {
        val credentials = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)
        credentials?.migrateLegacyKey(AutocompleteProviderType.ANTHROPIC, state.apiKey)
        credentials?.migrateLegacyKey(AutocompleteProviderType.INCEPTION_LABS, state.inceptionLabsApiKey)
        myState = state.sanitized()
    }

    val isConfigured: Boolean
        get() = hasInlineCapabilityConfigured()

    fun hasInlineCapabilityConfigured(isTerminal: Boolean = false): Boolean {
        val state = this.state
        return if (isTerminal) {
            state.terminalCompletionEnabled && isInlineProviderConfigured(state.terminalProvider)
        } else {
            isInlineProviderConfigured(state.resolvedInlineProvider()) ||
                hasInlineFallbackFromNextEdit(state)
        }
    }

    fun isInlineProviderConfigured(provider: AutocompleteProviderType = state.resolvedInlineProvider()): Boolean =
        when (provider) {
            AutocompleteProviderType.ANTHROPIC,
            AutocompleteProviderType.INCEPTION_LABS -> credentials().hasApiKey(provider)
        }

    fun isNextEditConfigured(): Boolean =
        when {
            !state.nextEditEnabled -> false
            else -> when (state.resolvedNextEditProvider()) {
            AutocompleteProviderType.ANTHROPIC -> false
            AutocompleteProviderType.INCEPTION_LABS -> credentials().hasApiKey(AutocompleteProviderType.INCEPTION_LABS)
            }
        }

    fun activeInlineProvider(): AutocompleteProviderType? {
        val state = this.state
        return when {
            isInlineProviderConfigured(state.resolvedInlineProvider()) -> state.resolvedInlineProvider()
            hasInlineFallbackFromNextEdit(state) -> state.resolvedNextEditProvider()
            else -> null
        }
    }

    fun apiKey(provider: AutocompleteProviderType): String = credentials().getApiKey(provider).orEmpty()

    private fun credentials(): ProviderCredentialsService =
        ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)

    private fun hasInlineFallbackFromNextEdit(state: State): Boolean =
        state.nextEditEnabled &&
            state.resolvedNextEditProvider() == AutocompleteProviderType.INCEPTION_LABS &&
            credentials().hasApiKey(AutocompleteProviderType.INCEPTION_LABS)

    companion object {
        const val DEFAULT_API_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        val DEFAULT_NEXT_EDIT_PROVIDER = AutocompleteProviderType.INCEPTION_LABS

        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
