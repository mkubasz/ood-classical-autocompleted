package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsGenerationOptions
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsNextEditContextOptions
import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.InceptionLabsAdvancedSettings
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.github.mkubasz.oodclassicalautocompleted.settings.ProviderCredentialsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

internal object NativeModelProviderFactory {
    private val log = logger<NativeModelProviderFactory>()

    fun createInlineProvider(state: PluginSettings.State): ModelProvider? = when (state.resolvedInlineProvider()) {
        AutocompleteProviderType.ANTHROPIC -> {
            val apiKey = credentials().getApiKey(AutocompleteProviderType.ANTHROPIC).orEmpty()
            if (apiKey.isBlank()) return null
            AnthropicModelProvider(
                id = "inline:${state.resolvedInlineProvider().name.lowercase()}",
                apiKey = apiKey,
                baseUrl = state.baseUrl.ifBlank { PluginSettings.DEFAULT_API_URL },
                model = state.model.ifBlank { PluginSettings.DEFAULT_MODEL },
                contextBudgetChars = state.contextBudgetChars,
            )
        }

        AutocompleteProviderType.INCEPTION_LABS -> {
            val apiKey = credentials().getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty()
            if (apiKey.isBlank()) return null
            InceptionLabsFimModelProvider(
                id = "inline:${state.resolvedInlineProvider().name.lowercase()}",
                apiKey = apiKey,
                baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsProviderDefaults.BASE_URL },
                model = state.inceptionLabsModel.ifBlank { InceptionLabsProviderDefaults.MODEL },
                generationOptions = safeFimOptions(state),
                contextBudgetChars = state.contextBudgetChars,
            )
        }
    }

    fun createTerminalProvider(state: PluginSettings.State): ModelProvider? {
        if (!state.terminalCompletionEnabled) return null
        return when (state.terminalProvider) {
            AutocompleteProviderType.ANTHROPIC -> {
                val apiKey = credentials().getApiKey(AutocompleteProviderType.ANTHROPIC).orEmpty()
                if (apiKey.isBlank()) return null
                AnthropicModelProvider(
                    id = "terminal:${state.terminalProvider.name.lowercase()}",
                    apiKey = apiKey,
                    baseUrl = state.baseUrl.ifBlank { PluginSettings.DEFAULT_API_URL },
                    model = state.model.ifBlank { PluginSettings.DEFAULT_MODEL },
                    contextBudgetChars = state.contextBudgetChars,
                )
            }

            AutocompleteProviderType.INCEPTION_LABS -> {
                val apiKey = credentials().getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty()
                if (apiKey.isBlank()) return null
                InceptionLabsFimModelProvider(
                    id = "terminal:${state.terminalProvider.name.lowercase()}",
                    apiKey = apiKey,
                    baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsProviderDefaults.BASE_URL },
                    model = state.inceptionLabsModel.ifBlank { InceptionLabsProviderDefaults.MODEL },
                    generationOptions = safeFimOptions(state),
                    contextBudgetChars = state.contextBudgetChars,
                )
            }
        }
    }

    fun createNextEditProvider(state: PluginSettings.State): ModelProvider? {
        if (!state.nextEditEnabled) return null
        if (state.resolvedNextEditProvider() != AutocompleteProviderType.INCEPTION_LABS) return null
        val apiKey = credentials().getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty()
        if (apiKey.isBlank()) return null
        return InceptionLabsNextEditModelProvider(
            id = "next_edit:${state.resolvedNextEditProvider().name.lowercase()}",
            apiKey = apiKey,
            baseUrl = state.inceptionLabsBaseUrl.ifBlank { InceptionLabsProviderDefaults.BASE_URL },
            model = state.inceptionLabsModel.ifBlank { InceptionLabsProviderDefaults.MODEL },
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

    private fun credentials(): ProviderCredentialsService =
        ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)
}
