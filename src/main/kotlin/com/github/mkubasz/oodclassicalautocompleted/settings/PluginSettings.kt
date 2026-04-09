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
        // Anthropic autocomplete
        var apiKey: String = "",
        var baseUrl: String = DEFAULT_API_URL,
        var model: String = DEFAULT_MODEL,
        // Autocompletion general
        var autocompleteEnabled: Boolean = true,
        var autocompleteProvider: AutocompleteProviderType = AutocompleteProviderType.ANTHROPIC,
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
        var correctnessFilterEnabled: Boolean = false,
        var minConfidenceScore: Double = 0.0,
        var contextBudgetChars: Int = 4_000,
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
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val isConfigured: Boolean
        get() = when (myState.autocompleteProvider) {
            AutocompleteProviderType.ANTHROPIC ->
                myState.apiKey.isNotBlank()
            AutocompleteProviderType.INCEPTION_LABS ->
                myState.inceptionLabsApiKey.isNotBlank()
        }

    companion object {
        const val DEFAULT_API_URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"

        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
