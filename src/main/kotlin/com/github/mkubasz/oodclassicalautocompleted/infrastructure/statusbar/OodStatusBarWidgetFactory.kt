package com.github.mkubasz.oodclassicalautocompleted.infrastructure.statusbar

import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteProviderType
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class OodStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "OodAutocompleteStatus"

    override fun getDisplayName(): String = "OOD Autocomplete Status"

    override fun createWidget(project: Project): StatusBarWidget = OodStatusBarWidget(project)

    override fun isAvailable(project: Project): Boolean = true
}

private class OodStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = "OodAutocompleteStatus"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val settings = PluginSettings.getInstance()
        if (!settings.isConfigured) return "OOD Autocomplete: Setup"

        return if (settings.state.autocompleteEnabled) {
            "OOD Autocomplete: ${providerLabel(settings)}"
        } else {
            "OOD Autocomplete: Off"
        }
    }

    override fun getTooltipText(): String {
        val settings = PluginSettings.getInstance()
        return buildString {
            append("OOD Autocomplete")
            if (!settings.isConfigured) {
                append(" is not configured. Click to open settings.")
            } else {
                append(" using ")
                append(activeModel(settings))
                append(". Click to open settings.")
            }
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "OOD Autocomplete")
    }

    override fun getAlignment(): Float = 0f

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {}

    private fun providerLabel(settings: PluginSettings): String =
        when (settings.state.autocompleteProvider) {
            AutocompleteProviderType.ANTHROPIC -> activeModel(settings).substringAfterLast('/')
            AutocompleteProviderType.INCEPTION_LABS -> activeModel(settings)
        }

    private fun activeModel(settings: PluginSettings): String =
        when (settings.state.autocompleteProvider) {
            AutocompleteProviderType.ANTHROPIC ->
                settings.state.model.ifBlank { PluginSettings.DEFAULT_MODEL }
            AutocompleteProviderType.INCEPTION_LABS ->
                settings.state.inceptionLabsModel.ifBlank { "Mercury" }
        }
}
