package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.InceptionLabsAdvancedSettings
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.diagnostic.logger

object AutocompleteProviderFactory {

    private val log = logger<AutocompleteProviderFactory>()

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
                generationOptions = safeFimOptions(state),
            )
        }
    }

    fun createTerminalProvider(state: PluginSettings.State): AutocompleteProvider? {
        if (!state.terminalCompletionEnabled) return null
        return when (state.terminalProvider) {
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
                    generationOptions = safeFimOptions(state),
                )
            }
        }
    }

    fun createNextEditProvider(state: PluginSettings.State): AutocompleteProvider? {
        if (state.autocompleteProvider != AutocompleteProviderType.INCEPTION_LABS) return null
        if (state.inceptionLabsApiKey.isBlank()) return null
        return InceptionLabsNextEditProvider(
            apiKey = state.inceptionLabsApiKey,
            baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsNextEditProvider.DEFAULT_BASE_URL },
            model = state.inceptionLabsModel.ifBlank { InceptionLabsNextEditProvider.DEFAULT_MODEL },
            generationOptions = safeNextEditOptions(state),
            contextOptions = safeNextEditContextOptions(state),
        )
    }

    private fun safeFimOptions(state: PluginSettings.State): InceptionLabsGenerationOptions =
        runCatching { InceptionLabsAdvancedSettings.fimOptionsFromState(state) }
            .getOrElse { error ->
                log.warn("Invalid Inception FIM settings. Falling back to defaults.", error)
                InceptionLabsGenerationOptions()
            }

    private fun safeNextEditOptions(state: PluginSettings.State): InceptionLabsGenerationOptions =
        runCatching { InceptionLabsAdvancedSettings.nextEditOptionsFromState(state) }
            .getOrElse { error ->
                log.warn("Invalid Inception Next Edit generation settings. Falling back to defaults.", error)
                InceptionLabsGenerationOptions()
            }

    private fun safeNextEditContextOptions(state: PluginSettings.State): InceptionLabsNextEditContextOptions =
        runCatching { InceptionLabsAdvancedSettings.nextEditContextOptionsFromState(state) }
            .getOrElse { error ->
                log.warn("Invalid Inception Next Edit context settings. Falling back to defaults.", error)
                InceptionLabsNextEditContextOptions()
            }
}
