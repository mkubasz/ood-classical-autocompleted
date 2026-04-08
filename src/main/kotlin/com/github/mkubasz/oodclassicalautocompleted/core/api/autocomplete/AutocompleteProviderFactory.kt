package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings

object AutocompleteProviderFactory {

    fun createFimProvider(state: PluginSettings.State): AutocompleteProvider? = when (state.autocompleteProvider) {
        AutocompleteProviderType.ANTHROPIC -> {
            if (state.apiKey.isBlank()) return null
            AnthropicAutocompleteProvider(
                apiKey = state.apiKey,
                baseUrl = state.baseUrl.ifBlank { PluginSettings.DEFAULT_API_URL },
                model = state.model.ifBlank { PluginSettings.DEFAULT_MODEL },
            )
        }

        AutocompleteProviderType.INCEPTION_LABS -> {
            if (state.inceptionLabsApiKey.isBlank()) return null
            InceptionLabsFimProvider(
                apiKey = state.inceptionLabsApiKey,
                baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsFimProvider.DEFAULT_BASE_URL },
                model = state.inceptionLabsModel.ifBlank { InceptionLabsFimProvider.DEFAULT_MODEL },
            )
        }
    }

    fun createNextEditProvider(state: PluginSettings.State): AutocompleteProvider? {
        if (state.autocompleteProvider != AutocompleteProviderType.INCEPTION_LABS) return null
        if (state.inceptionLabsApiKey.isBlank()) return null
        return InceptionLabsNextEditProvider(
            apiKey = state.inceptionLabsApiKey,
            baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsNextEditProvider.DEFAULT_BASE_URL },
            model = state.inceptionLabsModel.ifBlank { InceptionLabsNextEditProvider.DEFAULT_MODEL },
        )
    }
}
