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
        // Inception Labs autocomplete
        var inceptionLabsApiKey: String = "",
        var inceptionLabsBaseUrl: String = "",
        var inceptionLabsModel: String = "",
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
