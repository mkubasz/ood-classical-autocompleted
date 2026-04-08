package com.github.mkubasz.oodclassicalautocompleted.settings

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InceptionLabsFimProvider
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

class PluginSettingsConfigurable : Configurable {

    // Anthropic autocomplete fields
    private var apiKeyField: JBPasswordField? = null
    private var baseUrlField: JBTextField? = null
    private var modelField: JBTextField? = null

    // Autocompletion general
    private var autocompleteCheckbox: JBCheckBox? = null
    private var providerCombo: ComboBox<AutocompleteProviderType>? = null
    private var debounceField: JBTextField? = null

    // Inception Labs fields
    private var inceptionLabsApiKeyField: JBPasswordField? = null
    private var inceptionLabsBaseUrlField: JBTextField? = null
    private var inceptionLabsModelField: JBTextField? = null

    private var mainPanel: JPanel? = null

    // Rows for conditional visibility
    private var anthropicRows: List<Row> = emptyList()
    private var inceptionLabsRows: List<Row> = emptyList()

    override fun getDisplayName(): String = "OOD Autocomplete"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance()
        val state = settings.state

        apiKeyField = JBPasswordField().apply { text = state.apiKey; columns = 40 }
        baseUrlField = JBTextField(state.baseUrl, 40)
        modelField = JBTextField(state.model, 40)

        autocompleteCheckbox = JBCheckBox("Enable autocomplete", state.autocompleteEnabled)
        providerCombo = ComboBox(DefaultComboBoxModel(AutocompleteProviderType.entries.toTypedArray())).apply {
            selectedItem = state.autocompleteProvider
        }
        debounceField = JBTextField(state.debounceMs.toString(), 10)

        inceptionLabsApiKeyField = JBPasswordField().apply { text = state.inceptionLabsApiKey; columns = 40 }
        inceptionLabsBaseUrlField = JBTextField(state.inceptionLabsBaseUrl, 40)
        inceptionLabsModelField = JBTextField(state.inceptionLabsModel, 40)

        val anthropicRowList = mutableListOf<Row>()
        val inceptionLabsRowList = mutableListOf<Row>()

        mainPanel = panel {
            group("Anthropic") {
                anthropicRowList += row("API Key:") {
                    cell(apiKeyField!!)
                        .comment("Anthropic API key used for autocomplete")
                }
                anthropicRowList += row("Base URL:") {
                    cell(baseUrlField!!)
                        .comment("Anthropic autocomplete endpoint")
                }
                anthropicRowList += row("Model:") {
                    cell(modelField!!)
                        .comment("Model identifier for autocomplete")
                }
            }
            group("Autocomplete") {
                row {
                    cell(autocompleteCheckbox!!)
                }
                row("Provider:") {
                    cell(providerCombo!!)
                        .comment("Choose your autocomplete provider")
                }
                row("Debounce (ms):") {
                    cell(debounceField!!)
                        .comment("Delay before requesting completions")
                }
            }
            group("Inception Labs") {
                inceptionLabsRowList += row("API Key:") {
                    cell(inceptionLabsApiKeyField!!)
                        .comment("Your Inception Labs API key")
                }
                inceptionLabsRowList += row("Base URL:") {
                    cell(inceptionLabsBaseUrlField!!)
                        .comment("Default: https://api.inceptionlabs.ai/v1")
                }
                inceptionLabsRowList += row("Model:") {
                    cell(inceptionLabsModelField!!)
                        .comment("Default: ${InceptionLabsFimProvider.DEFAULT_MODEL}")
                }
            }
        }

        anthropicRows = anthropicRowList
        inceptionLabsRows = inceptionLabsRowList

        // Set initial visibility
        updateProviderVisibility()

        // Update visibility on provider change
        providerCombo!!.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateProviderVisibility()
            }
        }

        return mainPanel!!
    }

    private fun updateProviderVisibility() {
        val selected = providerCombo?.selectedItem as? AutocompleteProviderType ?: return
        val isAnthropic = selected == AutocompleteProviderType.ANTHROPIC
        val isInceptionLabs = selected == AutocompleteProviderType.INCEPTION_LABS

        anthropicRows.forEach { it.visible(isAnthropic) }
        inceptionLabsRows.forEach { it.visible(isInceptionLabs) }
    }

    override fun isModified(): Boolean {
        val state = PluginSettings.getInstance().state
        return String(apiKeyField?.password ?: charArrayOf()) != state.apiKey ||
            baseUrlField?.text != state.baseUrl ||
            modelField?.text != state.model ||
            autocompleteCheckbox?.isSelected != state.autocompleteEnabled ||
            providerCombo?.selectedItem != state.autocompleteProvider ||
            debounceField?.text != state.debounceMs.toString() ||
            String(inceptionLabsApiKeyField?.password ?: charArrayOf()) != state.inceptionLabsApiKey ||
            inceptionLabsBaseUrlField?.text != state.inceptionLabsBaseUrl ||
            inceptionLabsModelField?.text != state.inceptionLabsModel
    }

    override fun apply() {
        PluginSettings.getInstance().loadState(
            PluginSettings.State(
                apiKey = String(apiKeyField?.password ?: charArrayOf()),
                baseUrl = baseUrlField?.text ?: PluginSettings.DEFAULT_API_URL,
                model = modelField?.text ?: PluginSettings.DEFAULT_MODEL,
                autocompleteEnabled = autocompleteCheckbox?.isSelected ?: true,
                autocompleteProvider = providerCombo?.selectedItem as? AutocompleteProviderType
                    ?: AutocompleteProviderType.ANTHROPIC,
                debounceMs = debounceField?.text?.toLongOrNull() ?: 300L,
                inceptionLabsApiKey = String(inceptionLabsApiKeyField?.password ?: charArrayOf()),
                inceptionLabsBaseUrl = inceptionLabsBaseUrlField?.text ?: "",
                inceptionLabsModel = inceptionLabsModelField?.text ?: "",
            )
        )
    }

    override fun reset() {
        val state = PluginSettings.getInstance().state
        apiKeyField?.text = state.apiKey
        baseUrlField?.text = state.baseUrl
        modelField?.text = state.model
        autocompleteCheckbox?.isSelected = state.autocompleteEnabled
        providerCombo?.selectedItem = state.autocompleteProvider
        debounceField?.text = state.debounceMs.toString()
        inceptionLabsApiKeyField?.text = state.inceptionLabsApiKey
        inceptionLabsBaseUrlField?.text = state.inceptionLabsBaseUrl
        inceptionLabsModelField?.text = state.inceptionLabsModel
        updateProviderVisibility()
    }
}
