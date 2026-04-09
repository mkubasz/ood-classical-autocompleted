package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.github.mkubasz.oodclassicalautocompleted.settings.ProviderCredentialsService
import com.intellij.openapi.application.ApplicationManager

internal class ProviderRegistry {
    private var currentSettingsHash: Int? = null
    private var currentCredentialsVersion: Long? = null

    private var inlineProvider: ModelProvider? = null
    private var nextEditProvider: ModelProvider? = null
    private var terminalProvider: ModelProvider? = null

    fun ensureCurrent() {
        val settings = PluginSettings.getInstance().state
        val settingsHash = settings.hashCode()
        val credentialsVersion = ApplicationManager.getApplication()
            .getService(ProviderCredentialsService::class.java)
            .modificationCount
        if (settingsHash == currentSettingsHash && credentialsVersion == currentCredentialsVersion) return

        dispose()
        inlineProvider = NativeModelProviderFactory.createInlineProvider(settings)
        nextEditProvider = NativeModelProviderFactory.createNextEditProvider(settings)
        terminalProvider = NativeModelProviderFactory.createTerminalProvider(settings)
        currentSettingsHash = settingsHash
        currentCredentialsVersion = credentialsVersion
    }

    fun inlineSelection(isTerminal: Boolean): InlineProviderSelection {
        val settings = PluginSettings.getInstance().state
        val primary = if (isTerminal) {
            terminalProvider?.takeIf(ModelProvider::supportsInline)
                ?: inlineProvider?.takeIf(ModelProvider::supportsInline)
        } else {
            inlineProvider?.takeIf(ModelProvider::supportsInline)
        }
        val fallback = if (isTerminal) {
            null
        } else {
            nextEditProvider?.takeIf(ModelProvider::supportsInline)
        }

        val providerKey = if (isTerminal) {
            "terminal:${settings.terminalProvider.name}"
        } else {
            "inline:${settings.resolvedInlineProvider().name};next:${settings.resolvedNextEditProvider().name}"
        }
        return InlineProviderSelection(primary = primary, fallback = fallback, providerKey = providerKey)
    }

    fun nextEditProvider(): ModelProvider? =
        nextEditProvider?.takeIf(ModelProvider::supportsNextEdit)

    fun dispose() {
        inlineProvider?.dispose()
        nextEditProvider?.dispose()
        terminalProvider?.dispose()
        inlineProvider = null
        nextEditProvider = null
        terminalProvider = null
    }
}
