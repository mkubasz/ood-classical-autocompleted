package com.github.mkubasz.oodclassicalautocompleted.settings

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InceptionLabsFimProvider
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InceptionLabsNextEditContextOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

class PluginSettingsConfigurable : Configurable {

    private var apiKeyField: JBPasswordField? = null
    private var baseUrlField: JBTextField? = null
    private var modelField: JBTextField? = null

    private var autocompleteCheckbox: JBCheckBox? = null
    private var providerCombo: ComboBox<AutocompleteProviderType>? = null
    private var debounceField: JBTextField? = null
    private var dwellField: JBTextField? = null
    private var acceptOnRightArrowCheckbox: JBCheckBox? = null
    private var acceptOnEndKeyCheckbox: JBCheckBox? = null
    private var cycleNextShortcutField: JBTextField? = null
    private var cyclePreviousShortcutField: JBTextField? = null
    private var nextEditEnabledCheckbox: JBCheckBox? = null
    private var nextEditResolveImportsCheckbox: JBCheckBox? = null
    private var nextEditPreviewMaxLinesField: JBTextField? = null
    private var suggestionCacheTtlField: JBTextField? = null
    private var suggestionCacheMaxEntriesField: JBTextField? = null
    private var debugMetricsLoggingCheckbox: JBCheckBox? = null

    private var inceptionLabsApiKeyField: JBPasswordField? = null
    private var inceptionLabsBaseUrlField: JBTextField? = null
    private var inceptionLabsModelField: JBTextField? = null

    private var inceptionLabsFimMaxTokensField: JBTextField? = null
    private var inceptionLabsFimPresencePenaltyField: JBTextField? = null
    private var inceptionLabsFimTemperatureField: JBTextField? = null
    private var inceptionLabsFimTopPField: JBTextField? = null
    private var inceptionLabsFimStopSequencesArea: JBTextArea? = null
    private var inceptionLabsFimExtraBodyJsonArea: JBTextArea? = null

    private var inceptionLabsNextEditMaxTokensField: JBTextField? = null
    private var inceptionLabsNextEditPresencePenaltyField: JBTextField? = null
    private var inceptionLabsNextEditTemperatureField: JBTextField? = null
    private var inceptionLabsNextEditTopPField: JBTextField? = null
    private var inceptionLabsNextEditStopSequencesArea: JBTextArea? = null
    private var inceptionLabsNextEditExtraBodyJsonArea: JBTextArea? = null
    private var inceptionLabsNextEditLinesAboveField: JBTextField? = null
    private var inceptionLabsNextEditLinesBelowField: JBTextField? = null

    private var mainPanel: JPanel? = null

    private var anthropicRows: List<Row> = emptyList()
    private var inceptionLabsRows: List<Row> = emptyList()

    override fun getDisplayName(): String = "OOD Autocomplete"

    override fun createComponent(): JComponent {
        val state = PluginSettings.getInstance().state

        apiKeyField = JBPasswordField().apply { text = state.apiKey; columns = 40 }
        baseUrlField = JBTextField(state.baseUrl, 40)
        modelField = JBTextField(state.model, 40)

        autocompleteCheckbox = JBCheckBox("Enable autocomplete", state.autocompleteEnabled)
        providerCombo = ComboBox(DefaultComboBoxModel(AutocompleteProviderType.entries.toTypedArray())).apply {
            selectedItem = state.autocompleteProvider
        }
        debounceField = JBTextField(state.debounceMs.toString(), 10)
        dwellField = JBTextField(state.tabAcceptMinDwellMs.toString(), 10)
        acceptOnRightArrowCheckbox = JBCheckBox("Accept on Right Arrow", state.acceptOnRightArrow)
        acceptOnEndKeyCheckbox = JBCheckBox("Accept on End / Fn+Right", state.acceptOnEndKey)
        cycleNextShortcutField = JBTextField(state.cycleNextShortcut, 20)
        cyclePreviousShortcutField = JBTextField(state.cyclePreviousShortcut, 20)
        nextEditEnabledCheckbox = JBCheckBox("Enable Next Edit previews", state.nextEditEnabled)
        nextEditResolveImportsCheckbox = JBCheckBox(
            "Resolve imports after Next Edit apply",
            state.nextEditResolveImports,
        )
        nextEditPreviewMaxLinesField = numericField(state.nextEditPreviewMaxLines)
        suggestionCacheTtlField = JBTextField(state.suggestionCacheTtlMs.toString(), 10)
        suggestionCacheMaxEntriesField = numericField(state.suggestionCacheMaxEntries)
        debugMetricsLoggingCheckbox = JBCheckBox("Enable local debug metrics logging", state.debugMetricsLogging)

        inceptionLabsApiKeyField = JBPasswordField().apply { text = state.inceptionLabsApiKey; columns = 40 }
        inceptionLabsBaseUrlField = JBTextField(state.inceptionLabsBaseUrl, 40)
        inceptionLabsModelField = JBTextField(state.inceptionLabsModel, 40)

        inceptionLabsFimMaxTokensField = numericField(state.inceptionLabsFimMaxTokens)
        inceptionLabsFimPresencePenaltyField = numericField(state.inceptionLabsFimPresencePenalty)
        inceptionLabsFimTemperatureField = numericField(state.inceptionLabsFimTemperature)
        inceptionLabsFimTopPField = numericField(state.inceptionLabsFimTopP)
        inceptionLabsFimStopSequencesArea = textArea(state.inceptionLabsFimStopSequences, rows = 3)
        inceptionLabsFimExtraBodyJsonArea = textArea(state.inceptionLabsFimExtraBodyJson, rows = 5)

        inceptionLabsNextEditMaxTokensField = numericField(state.inceptionLabsNextEditMaxTokens)
        inceptionLabsNextEditPresencePenaltyField = numericField(state.inceptionLabsNextEditPresencePenalty)
        inceptionLabsNextEditTemperatureField = numericField(state.inceptionLabsNextEditTemperature)
        inceptionLabsNextEditTopPField = numericField(state.inceptionLabsNextEditTopP)
        inceptionLabsNextEditStopSequencesArea = textArea(state.inceptionLabsNextEditStopSequences, rows = 3)
        inceptionLabsNextEditExtraBodyJsonArea = textArea(state.inceptionLabsNextEditExtraBodyJson, rows = 5)
        inceptionLabsNextEditLinesAboveField = numericField(state.inceptionLabsNextEditLinesAboveCursor)
        inceptionLabsNextEditLinesBelowField = numericField(state.inceptionLabsNextEditLinesBelowCursor)

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
                row("Inline dwell (ms):") {
                    cell(dwellField!!)
                        .comment("Minimum time before an inline suggestion becomes visible")
                }
                row {
                    cell(nextEditEnabledCheckbox!!)
                }
                row {
                    cell(nextEditResolveImportsCheckbox!!)
                        .comment("Apply one IDE-provided import fix after a Next Edit if an unambiguous action is available")
                }
                row("Next Edit preview max lines:") {
                    cell(nextEditPreviewMaxLinesField!!)
                        .comment("Maximum replacement preview height before the next edit is suppressed")
                }
                row("Cache TTL (ms):") {
                    cell(suggestionCacheTtlField!!)
                        .comment("Lifetime for cached inline suggestions")
                }
                row("Cache max entries:") {
                    cell(suggestionCacheMaxEntriesField!!)
                        .comment("Maximum number of cached inline contexts")
                }
                row {
                    cell(debugMetricsLoggingCheckbox!!)
                }
            }

            group("Keyboard Shortcuts") {
                row {
                    cell(acceptOnRightArrowCheckbox!!)
                }
                row {
                    cell(acceptOnEndKeyCheckbox!!)
                }
                row("Next alternative:") {
                    cell(cycleNextShortcutField!!)
                        .comment("Optional. Examples: 'cmd+y', 'cmd+;', 'cmd+]', or 'ctrl+alt+.'. Leave blank to disable.")
                }
                row("Previous alternative:") {
                    cell(cyclePreviousShortcutField!!)
                        .comment("Optional. Examples: 'cmd+u', 'cmd+[', 'cmd+,', or 'ctrl+alt+,'. Leave blank to disable.")
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

            group("Inception FIM Advanced") {
                inceptionLabsRowList += row("Max tokens:") {
                    cell(inceptionLabsFimMaxTokensField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_MAX_TOKENS}. Range: 1-8192.")
                }
                inceptionLabsRowList += row("Presence penalty:") {
                    cell(inceptionLabsFimPresencePenaltyField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_PRESENCE_PENALTY}. Range: -2.0 to 2.0.")
                }
                inceptionLabsRowList += row("Temperature:") {
                    cell(inceptionLabsFimTemperatureField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_TEMPERATURE}. Range: 0.0 to 1.0.")
                }
                inceptionLabsRowList += row("Top-p:") {
                    cell(inceptionLabsFimTopPField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_TOP_P}. Range: 0.0 to 1.0.")
                }
                inceptionLabsRowList += row("Stop sequences:") {
                    cell(scrollPane(inceptionLabsFimStopSequencesArea!!))
                        .align(AlignX.FILL)
                        .comment("Optional. One sequence per line, up to 4.")
                }
                inceptionLabsRowList += row("Extra JSON:") {
                    cell(scrollPane(inceptionLabsFimExtraBodyJsonArea!!))
                        .align(AlignX.FILL)
                        .comment("Optional expert override. Top-level JSON object only. Reserved keys like model/prompt/stop and streaming keys are rejected.")
                }
            }

            group("Inception Next Edit Advanced") {
                inceptionLabsRowList += row("Max tokens:") {
                    cell(inceptionLabsNextEditMaxTokensField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_MAX_TOKENS}. Range: 1-8192.")
                }
                inceptionLabsRowList += row("Presence penalty:") {
                    cell(inceptionLabsNextEditPresencePenaltyField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_PRESENCE_PENALTY}. Range: -2.0 to 2.0.")
                }
                inceptionLabsRowList += row("Temperature:") {
                    cell(inceptionLabsNextEditTemperatureField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_TEMPERATURE}. Range: 0.0 to 1.0.")
                }
                inceptionLabsRowList += row("Top-p:") {
                    cell(inceptionLabsNextEditTopPField!!)
                        .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_TOP_P}. Range: 0.0 to 1.0.")
                }
                inceptionLabsRowList += row("Stop sequences:") {
                    cell(scrollPane(inceptionLabsNextEditStopSequencesArea!!))
                        .align(AlignX.FILL)
                        .comment("Optional. One sequence per line, up to 4.")
                }
                inceptionLabsRowList += row("Extra JSON:") {
                    cell(scrollPane(inceptionLabsNextEditExtraBodyJsonArea!!))
                        .align(AlignX.FILL)
                        .comment("Optional expert override. Top-level JSON object only. Reserved keys like model/messages/stop and streaming keys are rejected.")
                }
                inceptionLabsRowList += row("Lines above cursor:") {
                    cell(inceptionLabsNextEditLinesAboveField!!)
                        .comment("Default: ${InceptionLabsNextEditContextOptions.DEFAULT_LINES_ABOVE_CURSOR}. Must be at least 1.")
                }
                inceptionLabsRowList += row("Lines below cursor:") {
                    cell(inceptionLabsNextEditLinesBelowField!!)
                        .comment("Default: ${InceptionLabsNextEditContextOptions.DEFAULT_LINES_BELOW_CURSOR}. Must be at least 1.")
                }
            }
        }

        anthropicRows = anthropicRowList
        inceptionLabsRows = inceptionLabsRowList

        updateProviderVisibility()
        providerCombo!!.addItemListener { event ->
            if (event.stateChange == ItemEvent.SELECTED) {
                updateProviderVisibility()
            }
        }

        return mainPanel!!
    }

    private fun updateProviderVisibility() {
        val selected = providerCombo?.selectedItem as? AutocompleteProviderType ?: return
        anthropicRows.forEach { it.visible(selected == AutocompleteProviderType.ANTHROPIC) }
        inceptionLabsRows.forEach { it.visible(selected == AutocompleteProviderType.INCEPTION_LABS) }
    }

    override fun isModified(): Boolean {
        val state = PluginSettings.getInstance().state
        return String(apiKeyField?.password ?: charArrayOf()) != state.apiKey ||
            baseUrlField?.text != state.baseUrl ||
            modelField?.text != state.model ||
            autocompleteCheckbox?.isSelected != state.autocompleteEnabled ||
            providerCombo?.selectedItem != state.autocompleteProvider ||
            debounceField?.text != state.debounceMs.toString() ||
            dwellField?.text != state.tabAcceptMinDwellMs.toString() ||
            acceptOnRightArrowCheckbox?.isSelected != state.acceptOnRightArrow ||
            acceptOnEndKeyCheckbox?.isSelected != state.acceptOnEndKey ||
            cycleNextShortcutField?.text != state.cycleNextShortcut ||
            cyclePreviousShortcutField?.text != state.cyclePreviousShortcut ||
            nextEditEnabledCheckbox?.isSelected != state.nextEditEnabled ||
            nextEditResolveImportsCheckbox?.isSelected != state.nextEditResolveImports ||
            nextEditPreviewMaxLinesField?.text != state.nextEditPreviewMaxLines.toString() ||
            suggestionCacheTtlField?.text != state.suggestionCacheTtlMs.toString() ||
            suggestionCacheMaxEntriesField?.text != state.suggestionCacheMaxEntries.toString() ||
            debugMetricsLoggingCheckbox?.isSelected != state.debugMetricsLogging ||
            String(inceptionLabsApiKeyField?.password ?: charArrayOf()) != state.inceptionLabsApiKey ||
            inceptionLabsBaseUrlField?.text != state.inceptionLabsBaseUrl ||
            inceptionLabsModelField?.text != state.inceptionLabsModel ||
            inceptionLabsFimMaxTokensField?.text != state.inceptionLabsFimMaxTokens.toFieldValue() ||
            inceptionLabsFimPresencePenaltyField?.text != state.inceptionLabsFimPresencePenalty.toFieldValue() ||
            inceptionLabsFimTemperatureField?.text != state.inceptionLabsFimTemperature.toFieldValue() ||
            inceptionLabsFimTopPField?.text != state.inceptionLabsFimTopP.toFieldValue() ||
            inceptionLabsFimStopSequencesArea?.text != state.inceptionLabsFimStopSequences ||
            inceptionLabsFimExtraBodyJsonArea?.text != state.inceptionLabsFimExtraBodyJson ||
            inceptionLabsNextEditMaxTokensField?.text != state.inceptionLabsNextEditMaxTokens.toFieldValue() ||
            inceptionLabsNextEditPresencePenaltyField?.text != state.inceptionLabsNextEditPresencePenalty.toFieldValue() ||
            inceptionLabsNextEditTemperatureField?.text != state.inceptionLabsNextEditTemperature.toFieldValue() ||
            inceptionLabsNextEditTopPField?.text != state.inceptionLabsNextEditTopP.toFieldValue() ||
            inceptionLabsNextEditStopSequencesArea?.text != state.inceptionLabsNextEditStopSequences ||
            inceptionLabsNextEditExtraBodyJsonArea?.text != state.inceptionLabsNextEditExtraBodyJson ||
            inceptionLabsNextEditLinesAboveField?.text != state.inceptionLabsNextEditLinesAboveCursor.toString() ||
            inceptionLabsNextEditLinesBelowField?.text != state.inceptionLabsNextEditLinesBelowCursor.toString()
    }

    override fun apply() {
        val normalizedNextShortcut = normalizeShortcut(
            rawValue = cycleNextShortcutField?.text ?: "",
            fieldName = "Next alternative shortcut",
        )
        val normalizedPreviousShortcut = normalizeShortcut(
            rawValue = cyclePreviousShortcutField?.text ?: "",
            fieldName = "Previous alternative shortcut",
        )

        val newState = PluginSettings.State(
            apiKey = String(apiKeyField?.password ?: charArrayOf()),
            baseUrl = baseUrlField?.text ?: PluginSettings.DEFAULT_API_URL,
            model = modelField?.text ?: PluginSettings.DEFAULT_MODEL,
            autocompleteEnabled = autocompleteCheckbox?.isSelected ?: true,
            autocompleteProvider = providerCombo?.selectedItem as? AutocompleteProviderType
                ?: AutocompleteProviderType.ANTHROPIC,
            debounceMs = debounceField?.text?.toLongOrNull() ?: 300L,
            tabAcceptMinDwellMs = parseRequiredLong(
                dwellField?.text,
                "Inline dwell",
            ),
            acceptOnRightArrow = acceptOnRightArrowCheckbox?.isSelected ?: true,
            acceptOnEndKey = acceptOnEndKeyCheckbox?.isSelected ?: true,
            cycleNextShortcut = normalizedNextShortcut,
            cyclePreviousShortcut = normalizedPreviousShortcut,
            nextEditEnabled = nextEditEnabledCheckbox?.isSelected ?: true,
            nextEditResolveImports = nextEditResolveImportsCheckbox?.isSelected ?: true,
            nextEditPreviewMaxLines = parseRequiredInt(
                nextEditPreviewMaxLinesField?.text,
                "Next Edit preview max lines",
            ),
            suggestionCacheTtlMs = parseRequiredLong(
                suggestionCacheTtlField?.text,
                "Cache TTL",
            ),
            suggestionCacheMaxEntries = parseRequiredInt(
                suggestionCacheMaxEntriesField?.text,
                "Cache max entries",
            ),
            debugMetricsLogging = debugMetricsLoggingCheckbox?.isSelected ?: false,
            inceptionLabsApiKey = String(inceptionLabsApiKeyField?.password ?: charArrayOf()),
            inceptionLabsBaseUrl = inceptionLabsBaseUrlField?.text ?: "",
            inceptionLabsModel = inceptionLabsModelField?.text ?: "",
            inceptionLabsFimMaxTokens = parseOptionalInt(
                inceptionLabsFimMaxTokensField?.text,
                "Inception FIM max tokens",
            ),
            inceptionLabsFimPresencePenalty = parseOptionalDouble(
                inceptionLabsFimPresencePenaltyField?.text,
                "Inception FIM presence penalty",
            ),
            inceptionLabsFimTemperature = parseOptionalDouble(
                inceptionLabsFimTemperatureField?.text,
                "Inception FIM temperature",
            ),
            inceptionLabsFimTopP = parseOptionalDouble(
                inceptionLabsFimTopPField?.text,
                "Inception FIM top-p",
            ),
            inceptionLabsFimStopSequences = inceptionLabsFimStopSequencesArea?.text ?: "",
            inceptionLabsFimExtraBodyJson = inceptionLabsFimExtraBodyJsonArea?.text ?: "",
            inceptionLabsNextEditMaxTokens = parseOptionalInt(
                inceptionLabsNextEditMaxTokensField?.text,
                "Inception Next Edit max tokens",
            ),
            inceptionLabsNextEditPresencePenalty = parseOptionalDouble(
                inceptionLabsNextEditPresencePenaltyField?.text,
                "Inception Next Edit presence penalty",
            ),
            inceptionLabsNextEditTemperature = parseOptionalDouble(
                inceptionLabsNextEditTemperatureField?.text,
                "Inception Next Edit temperature",
            ),
            inceptionLabsNextEditTopP = parseOptionalDouble(
                inceptionLabsNextEditTopPField?.text,
                "Inception Next Edit top-p",
            ),
            inceptionLabsNextEditStopSequences = inceptionLabsNextEditStopSequencesArea?.text ?: "",
            inceptionLabsNextEditExtraBodyJson = inceptionLabsNextEditExtraBodyJsonArea?.text ?: "",
            inceptionLabsNextEditLinesAboveCursor = parseRequiredInt(
                inceptionLabsNextEditLinesAboveField?.text,
                "Next Edit lines above cursor",
            ),
            inceptionLabsNextEditLinesBelowCursor = parseRequiredInt(
                inceptionLabsNextEditLinesBelowField?.text,
                "Next Edit lines below cursor",
            ),
        )

        try {
            InceptionLabsAdvancedSettings.validateState(newState)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException(e.message)
        }

        cycleNextShortcutField?.text = normalizedNextShortcut
        cyclePreviousShortcutField?.text = normalizedPreviousShortcut

        PluginSettings.getInstance().loadState(newState)
        ApplicationManager.getApplication()
            .getService(AutocompleteShortcutManager::class.java)
            .applySettings(newState)
    }

    override fun reset() {
        val state = PluginSettings.getInstance().state
        apiKeyField?.text = state.apiKey
        baseUrlField?.text = state.baseUrl
        modelField?.text = state.model
        autocompleteCheckbox?.isSelected = state.autocompleteEnabled
        providerCombo?.selectedItem = state.autocompleteProvider
        debounceField?.text = state.debounceMs.toString()
        dwellField?.text = state.tabAcceptMinDwellMs.toString()
        acceptOnRightArrowCheckbox?.isSelected = state.acceptOnRightArrow
        acceptOnEndKeyCheckbox?.isSelected = state.acceptOnEndKey
        cycleNextShortcutField?.text = state.cycleNextShortcut
        cyclePreviousShortcutField?.text = state.cyclePreviousShortcut
        nextEditEnabledCheckbox?.isSelected = state.nextEditEnabled
        nextEditResolveImportsCheckbox?.isSelected = state.nextEditResolveImports
        nextEditPreviewMaxLinesField?.text = state.nextEditPreviewMaxLines.toString()
        suggestionCacheTtlField?.text = state.suggestionCacheTtlMs.toString()
        suggestionCacheMaxEntriesField?.text = state.suggestionCacheMaxEntries.toString()
        debugMetricsLoggingCheckbox?.isSelected = state.debugMetricsLogging

        inceptionLabsApiKeyField?.text = state.inceptionLabsApiKey
        inceptionLabsBaseUrlField?.text = state.inceptionLabsBaseUrl
        inceptionLabsModelField?.text = state.inceptionLabsModel

        inceptionLabsFimMaxTokensField?.text = state.inceptionLabsFimMaxTokens.toFieldValue()
        inceptionLabsFimPresencePenaltyField?.text = state.inceptionLabsFimPresencePenalty.toFieldValue()
        inceptionLabsFimTemperatureField?.text = state.inceptionLabsFimTemperature.toFieldValue()
        inceptionLabsFimTopPField?.text = state.inceptionLabsFimTopP.toFieldValue()
        inceptionLabsFimStopSequencesArea?.text = state.inceptionLabsFimStopSequences
        inceptionLabsFimExtraBodyJsonArea?.text = state.inceptionLabsFimExtraBodyJson

        inceptionLabsNextEditMaxTokensField?.text = state.inceptionLabsNextEditMaxTokens.toFieldValue()
        inceptionLabsNextEditPresencePenaltyField?.text = state.inceptionLabsNextEditPresencePenalty.toFieldValue()
        inceptionLabsNextEditTemperatureField?.text = state.inceptionLabsNextEditTemperature.toFieldValue()
        inceptionLabsNextEditTopPField?.text = state.inceptionLabsNextEditTopP.toFieldValue()
        inceptionLabsNextEditStopSequencesArea?.text = state.inceptionLabsNextEditStopSequences
        inceptionLabsNextEditExtraBodyJsonArea?.text = state.inceptionLabsNextEditExtraBodyJson
        inceptionLabsNextEditLinesAboveField?.text = state.inceptionLabsNextEditLinesAboveCursor.toString()
        inceptionLabsNextEditLinesBelowField?.text = state.inceptionLabsNextEditLinesBelowCursor.toString()

        updateProviderVisibility()
    }

    private fun normalizeShortcut(rawValue: String, fieldName: String): String = try {
        AutocompleteActionShortcuts.normalizeShortcut(rawValue, fieldName)
    } catch (e: IllegalArgumentException) {
        throw ConfigurationException(e.message)
    }

    private fun parseOptionalInt(rawValue: String?, fieldName: String): Int? {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return normalized.toIntOrNull()
            ?: throw ConfigurationException("$fieldName must be a whole number.")
    }

    private fun parseRequiredInt(rawValue: String?, fieldName: String): Int {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw ConfigurationException("$fieldName is required.")
        }
        return normalized.toIntOrNull()
            ?: throw ConfigurationException("$fieldName must be a whole number.")
    }

    private fun parseRequiredLong(rawValue: String?, fieldName: String): Long {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw ConfigurationException("$fieldName is required.")
        }
        return normalized.toLongOrNull()
            ?: throw ConfigurationException("$fieldName must be a whole number.")
    }

    private fun parseOptionalDouble(rawValue: String?, fieldName: String): Double? {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return normalized.toDoubleOrNull()
            ?: throw ConfigurationException("$fieldName must be a number.")
    }

    private fun numericField(value: Number?): JBTextField =
        JBTextField(value?.toString().orEmpty(), 10)

    private fun textArea(value: String, rows: Int): JBTextArea =
        JBTextArea(value, rows, 40).apply {
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }

    private fun scrollPane(textArea: JBTextArea): JBScrollPane =
        JBScrollPane(textArea).apply {
            preferredSize = Dimension(420, textArea.preferredSize.height + 18)
        }

    private fun Number?.toFieldValue(): String = this?.toString().orEmpty()
}
