package com.github.mkubasz.oodclassicalautocompleted.settings

import com.github.mkubasz.oodclassicalautocompleted.completion.providers.InceptionLabsProviderDefaults
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsNextEditContextOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
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
    private var inlineProviderCombo: ComboBox<AutocompleteProviderType>? = null
    private var nextEditProviderCombo: ComboBox<AutocompleteProviderType>? = null
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
    private var terminalCompletionCheckbox: JBCheckBox? = null
    private var terminalProviderCombo: ComboBox<AutocompleteProviderType>? = null
    private var gitDiffContextCheckbox: JBCheckBox? = null
    private var correctnessFilterCheckbox: JBCheckBox? = null
    private var minConfidenceScoreField: JBTextField? = null
    private var contextBudgetCharsField: JBTextField? = null
    private var lspContextFallbackCheckbox: JBCheckBox? = null
    private var localRetrievalCheckbox: JBCheckBox? = null
    private var retrievalMaxChunksField: JBTextField? = null

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
    private var inceptionLabsNextEditDiffusingCheckbox: JBCheckBox? = null
    private var inceptionLabsNextEditReasoningEffortField: JBTextField? = null

    private var tabbedPane: JBTabbedPane? = null

    override fun getDisplayName(): String = "OOD Autocomplete"

    override fun createComponent(): JComponent {
        val state = PluginSettings.getInstance().state
        val credentials = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)

        apiKeyField = JBPasswordField().apply { text = credentials.getApiKey(AutocompleteProviderType.ANTHROPIC).orEmpty(); columns = 40 }
        baseUrlField = JBTextField(state.baseUrl, 40)
        modelField = JBTextField(state.model, 40)

        autocompleteCheckbox = JBCheckBox("Enable autocomplete", state.autocompleteEnabled)
        inlineProviderCombo = ComboBox(DefaultComboBoxModel(AutocompleteProviderType.entries.toTypedArray())).apply {
            selectedItem = state.resolvedInlineProvider()
        }
        nextEditProviderCombo = ComboBox(DefaultComboBoxModel(AutocompleteProviderType.entries.toTypedArray())).apply {
            selectedItem = state.resolvedNextEditProvider()
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
        terminalCompletionCheckbox = JBCheckBox("Enable completion in terminal", state.terminalCompletionEnabled)
        terminalProviderCombo = ComboBox(DefaultComboBoxModel(AutocompleteProviderType.entries.toTypedArray())).apply {
            selectedItem = state.terminalProvider
        }
        gitDiffContextCheckbox = JBCheckBox("Include git diff context in Next Edit requests", state.gitDiffContextEnabled)
        correctnessFilterCheckbox = JBCheckBox("Enable correctness filter", state.correctnessFilterEnabled)
        minConfidenceScoreField = numericField(state.minConfidenceScore)
        contextBudgetCharsField = numericField(state.contextBudgetChars)
        lspContextFallbackCheckbox = JBCheckBox(
            "Enable LSP semantic context fallback",
            state.lspContextFallbackEnabled,
        )
        localRetrievalCheckbox = JBCheckBox(
            "Enable local workspace retrieval",
            state.localRetrievalEnabled,
        )
        retrievalMaxChunksField = numericField(state.retrievalMaxChunks)

        inceptionLabsApiKeyField = JBPasswordField().apply {
            text = credentials.getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty()
            columns = 40
        }
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
        inceptionLabsNextEditDiffusingCheckbox = JBCheckBox("Enable diffusing", state.inceptionLabsNextEditDiffusing)
        inceptionLabsNextEditReasoningEffortField = JBTextField(state.inceptionLabsNextEditReasoningEffort, 10)

        tabbedPane = JBTabbedPane().apply {
            addTab("General", buildGeneralTab())
            addTab("Anthropic", buildAnthropicTab())
            addTab("Inception Labs", buildInceptionLabsTab())
            addTab("Inception Advanced", buildInceptionAdvancedTab())
        }
        return tabbedPane!!
    }

    private fun buildGeneralTab(): JComponent = panel {
        group("Provider") {
            row { cell(autocompleteCheckbox!!) }
            row("Inline provider:") {
                cell(inlineProviderCombo!!)
                    .comment("Primary provider for inline code completion")
            }
            row("Next Edit provider:") {
                cell(nextEditProviderCombo!!)
                    .comment("Currently only Inception Labs supports Next Edit previews")
            }
        }
        group("Behavior") {
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
            }
            row("Next Edit preview max lines:") {
                cell(nextEditPreviewMaxLinesField!!)
            }
            row {
                cell(gitDiffContextCheckbox!!)
                    .comment("Opt-in VCS grounding for Next Edit requests")
            }
        }
        group("Cache") {
            row("Cache TTL (ms):") {
                cell(suggestionCacheTtlField!!)
            }
            row("Cache max entries:") {
                cell(suggestionCacheMaxEntriesField!!)
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
                    .comment("Optional. Examples: 'cmd+y', 'cmd+;', or 'ctrl+alt+.'")
            }
            row("Previous alternative:") {
                cell(cyclePreviousShortcutField!!)
                    .comment("Optional. Examples: 'cmd+u', 'cmd+[', or 'ctrl+alt+,'")
            }
        }
        group("Experimental") {
            row {
                cell(terminalCompletionCheckbox!!)
                    .comment("Provide AI completions in the integrated terminal (experimental)")
            }
            row("Terminal provider:") {
                cell(terminalProviderCombo!!)
                    .comment("Leave as-is to use the same provider as code completion")
            }
            row {
                cell(debugMetricsLoggingCheckbox!!)
            }
            row {
                cell(correctnessFilterCheckbox!!)
                    .comment("Run temporary PSI validation on prepared inline candidates")
            }
            row("Min confidence score:") {
                cell(minConfidenceScoreField!!)
                    .comment("Range: 0.0 to 1.0. Use 0.0 to disable score-based filtering.")
            }
            row("Context budget (chars):") {
                cell(contextBudgetCharsField!!)
                    .comment("Shared prompt budget for inline providers")
            }
            row {
                cell(lspContextFallbackCheckbox!!)
                    .comment("Try PSI first, then LSP-backed context when available for weak PSI languages")
            }
            row {
                cell(localRetrievalCheckbox!!)
                    .comment("Retrieve related project snippets from other workspace files for additional grounding")
            }
            row("Retrieval max chunks:") {
                cell(retrievalMaxChunksField!!)
                    .comment("Number of retrieved snippets to include when retrieval is enabled")
            }
        }
    }

    private fun buildAnthropicTab(): JComponent = panel {
        group("Connection") {
            row("API Key:") {
                cell(apiKeyField!!)
                    .comment("Anthropic API key used for autocomplete")
            }
            row("Base URL:") {
                cell(baseUrlField!!)
                    .comment("Default: ${PluginSettings.DEFAULT_API_URL}")
            }
            row("Model:") {
                cell(modelField!!)
                    .comment("Default: ${PluginSettings.DEFAULT_MODEL}")
            }
        }
    }

    private fun buildInceptionLabsTab(): JComponent = panel {
        group("Connection") {
            row("API Key:") {
                cell(inceptionLabsApiKeyField!!)
                    .comment("Your Inception Labs API key")
            }
            row("Base URL:") {
                cell(inceptionLabsBaseUrlField!!)
                    .comment("Default: ${InceptionLabsProviderDefaults.BASE_URL}")
            }
            row("Model:") {
                cell(inceptionLabsModelField!!)
                    .comment("Default: ${InceptionLabsProviderDefaults.MODEL}")
            }
        }
    }

    private fun buildInceptionAdvancedTab(): JComponent = panel {
        group("FIM Completion") {
            row("Max tokens:") {
                cell(inceptionLabsFimMaxTokensField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_MAX_TOKENS}. Range: 1-8192.")
            }
            row("Presence penalty:") {
                cell(inceptionLabsFimPresencePenaltyField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_PRESENCE_PENALTY}. Range: -2.0 to 2.0.")
            }
            row("Temperature:") {
                cell(inceptionLabsFimTemperatureField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_TEMPERATURE}. Range: 0.0 to 1.0.")
            }
            row("Top-p:") {
                cell(inceptionLabsFimTopPField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.FIM_DEFAULT_TOP_P}. Range: 0.0 to 1.0.")
            }
            row("Stop sequences:") {
                cell(scrollPane(inceptionLabsFimStopSequencesArea!!))
                    .align(AlignX.FILL)
                    .comment("One per line, up to 4.")
            }
            row("Extra JSON:") {
                cell(scrollPane(inceptionLabsFimExtraBodyJsonArea!!))
                    .align(AlignX.FILL)
                    .comment("Expert override. Reserved keys rejected.")
            }
        }
        group("Next Edit") {
            row("Max tokens:") {
                cell(inceptionLabsNextEditMaxTokensField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_MAX_TOKENS}. Range: 1-8192.")
            }
            row("Presence penalty:") {
                cell(inceptionLabsNextEditPresencePenaltyField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_PRESENCE_PENALTY}. Range: -2.0 to 2.0.")
            }
            row("Temperature:") {
                cell(inceptionLabsNextEditTemperatureField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_TEMPERATURE}. Range: 0.0 to 1.0.")
            }
            row("Top-p:") {
                cell(inceptionLabsNextEditTopPField!!)
                    .comment("Optional. Default: ${InceptionLabsAdvancedSettings.NEXT_EDIT_DEFAULT_TOP_P}. Range: 0.0 to 1.0.")
            }
            row("Stop sequences:") {
                cell(scrollPane(inceptionLabsNextEditStopSequencesArea!!))
                    .align(AlignX.FILL)
                    .comment("One per line, up to 4.")
            }
            row("Extra JSON:") {
                cell(scrollPane(inceptionLabsNextEditExtraBodyJsonArea!!))
                    .align(AlignX.FILL)
                    .comment("Expert override. Reserved keys rejected.")
            }
            row("Lines above cursor:") {
                cell(inceptionLabsNextEditLinesAboveField!!)
                    .comment("Default: ${InceptionLabsNextEditContextOptions.DEFAULT_LINES_ABOVE_CURSOR}")
            }
            row("Lines below cursor:") {
                cell(inceptionLabsNextEditLinesBelowField!!)
                    .comment("Default: ${InceptionLabsNextEditContextOptions.DEFAULT_LINES_BELOW_CURSOR}")
            }
            row {
                cell(inceptionLabsNextEditDiffusingCheckbox!!)
                    .comment("Enable diffusing mode for NextEdit completions")
            }
            row("Reasoning effort:") {
                cell(inceptionLabsNextEditReasoningEffortField!!)
                    .comment("low, medium, or high. Default: low")
            }
        }
    }

    override fun isModified(): Boolean {
        val state = PluginSettings.getInstance().state
        val credentials = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)
        return String(apiKeyField?.password ?: charArrayOf()) != credentials.getApiKey(AutocompleteProviderType.ANTHROPIC).orEmpty() ||
            baseUrlField?.text != state.baseUrl ||
            modelField?.text != state.model ||
            autocompleteCheckbox?.isSelected != state.autocompleteEnabled ||
            inlineProviderCombo?.selectedItem != state.resolvedInlineProvider() ||
            nextEditProviderCombo?.selectedItem != state.resolvedNextEditProvider() ||
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
            terminalCompletionCheckbox?.isSelected != state.terminalCompletionEnabled ||
            terminalProviderCombo?.selectedItem != state.terminalProvider ||
            gitDiffContextCheckbox?.isSelected != state.gitDiffContextEnabled ||
            correctnessFilterCheckbox?.isSelected != state.correctnessFilterEnabled ||
            minConfidenceScoreField?.text != state.minConfidenceScore.toFieldValue() ||
            contextBudgetCharsField?.text != state.contextBudgetChars.toString() ||
            lspContextFallbackCheckbox?.isSelected != state.lspContextFallbackEnabled ||
            localRetrievalCheckbox?.isSelected != state.localRetrievalEnabled ||
            retrievalMaxChunksField?.text != state.retrievalMaxChunks.toString() ||
            String(inceptionLabsApiKeyField?.password ?: charArrayOf()) != credentials.getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty() ||
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
            inceptionLabsNextEditLinesBelowField?.text != state.inceptionLabsNextEditLinesBelowCursor.toString() ||
            inceptionLabsNextEditDiffusingCheckbox?.isSelected != state.inceptionLabsNextEditDiffusing ||
            inceptionLabsNextEditReasoningEffortField?.text != state.inceptionLabsNextEditReasoningEffort
    }

    override fun apply() {
        val credentials = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)
        val normalizedNextShortcut = normalizeShortcut(
            rawValue = cycleNextShortcutField?.text ?: "",
            fieldName = "Next alternative shortcut",
        )
        val normalizedPreviousShortcut = normalizeShortcut(
            rawValue = cyclePreviousShortcutField?.text ?: "",
            fieldName = "Previous alternative shortcut",
        )

        val newState = PluginSettings.State(
            baseUrl = baseUrlField?.text ?: PluginSettings.DEFAULT_API_URL,
            model = modelField?.text ?: PluginSettings.DEFAULT_MODEL,
            autocompleteEnabled = autocompleteCheckbox?.isSelected ?: true,
            autocompleteProvider = inlineProviderCombo?.selectedItem as? AutocompleteProviderType
                ?: AutocompleteProviderType.ANTHROPIC,
            inlineProvider = inlineProviderCombo?.selectedItem as? AutocompleteProviderType
                ?: AutocompleteProviderType.ANTHROPIC,
            nextEditProvider = nextEditProviderCombo?.selectedItem as? AutocompleteProviderType
                ?: PluginSettings.DEFAULT_NEXT_EDIT_PROVIDER,
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
            terminalCompletionEnabled = terminalCompletionCheckbox?.isSelected ?: false,
            terminalProvider = terminalProviderCombo?.selectedItem as? AutocompleteProviderType
                ?: AutocompleteProviderType.ANTHROPIC,
            gitDiffContextEnabled = gitDiffContextCheckbox?.isSelected ?: false,
            correctnessFilterEnabled = correctnessFilterCheckbox?.isSelected ?: false,
            minConfidenceScore = parseRequiredDouble(
                minConfidenceScoreField?.text,
                "Min confidence score",
                min = 0.0,
                max = 1.0,
            ),
            contextBudgetChars = parseRequiredInt(
                contextBudgetCharsField?.text,
                "Context budget",
            ),
            lspContextFallbackEnabled = lspContextFallbackCheckbox?.isSelected ?: false,
            localRetrievalEnabled = localRetrievalCheckbox?.isSelected ?: false,
            retrievalMaxChunks = parseRequiredInt(
                retrievalMaxChunksField?.text,
                "Retrieval max chunks",
            ),
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
            inceptionLabsNextEditDiffusing = inceptionLabsNextEditDiffusingCheckbox?.isSelected ?: false,
            inceptionLabsNextEditReasoningEffort = inceptionLabsNextEditReasoningEffortField?.text?.trim() ?: "low",
        )

        if (newState.contextBudgetChars < 1_200) {
            throw ConfigurationException("Context budget must be at least 1200.")
        }
        if (newState.retrievalMaxChunks !in 1..8) {
            throw ConfigurationException("Retrieval max chunks must be between 1 and 8.")
        }

        try {
            InceptionLabsAdvancedSettings.validateState(newState)
        } catch (e: IllegalArgumentException) {
            throw ConfigurationException(e.message)
        }

        cycleNextShortcutField?.text = normalizedNextShortcut
        cyclePreviousShortcutField?.text = normalizedPreviousShortcut

        credentials.setApiKey(
            AutocompleteProviderType.ANTHROPIC,
            String(apiKeyField?.password ?: charArrayOf()),
        )
        credentials.setApiKey(
            AutocompleteProviderType.INCEPTION_LABS,
            String(inceptionLabsApiKeyField?.password ?: charArrayOf()),
        )
        PluginSettings.getInstance().loadState(newState)
        ApplicationManager.getApplication()
            .getService(AutocompleteShortcutManager::class.java)
            .applySettings(newState)
    }

    override fun reset() {
        val state = PluginSettings.getInstance().state
        val credentials = ApplicationManager.getApplication().getService(ProviderCredentialsService::class.java)
        apiKeyField?.text = credentials.getApiKey(AutocompleteProviderType.ANTHROPIC).orEmpty()
        baseUrlField?.text = state.baseUrl
        modelField?.text = state.model
        autocompleteCheckbox?.isSelected = state.autocompleteEnabled
        inlineProviderCombo?.selectedItem = state.resolvedInlineProvider()
        nextEditProviderCombo?.selectedItem = state.resolvedNextEditProvider()
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
        terminalCompletionCheckbox?.isSelected = state.terminalCompletionEnabled
        terminalProviderCombo?.selectedItem = state.terminalProvider
        gitDiffContextCheckbox?.isSelected = state.gitDiffContextEnabled
        correctnessFilterCheckbox?.isSelected = state.correctnessFilterEnabled
        minConfidenceScoreField?.text = state.minConfidenceScore.toFieldValue()
        contextBudgetCharsField?.text = state.contextBudgetChars.toString()
        lspContextFallbackCheckbox?.isSelected = state.lspContextFallbackEnabled
        localRetrievalCheckbox?.isSelected = state.localRetrievalEnabled
        retrievalMaxChunksField?.text = state.retrievalMaxChunks.toString()

        inceptionLabsApiKeyField?.text = credentials.getApiKey(AutocompleteProviderType.INCEPTION_LABS).orEmpty()
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
        inceptionLabsNextEditDiffusingCheckbox?.isSelected = state.inceptionLabsNextEditDiffusing
        inceptionLabsNextEditReasoningEffortField?.text = state.inceptionLabsNextEditReasoningEffort

        // Tab layout handles provider visibility
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

    private fun parseRequiredDouble(rawValue: String?, fieldName: String, min: Double, max: Double): Double {
        val normalized = rawValue?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw ConfigurationException("$fieldName is required.")
        }
        val value = normalized.toDoubleOrNull()
            ?: throw ConfigurationException("$fieldName must be a number.")
        if (value !in min..max) {
            throw ConfigurationException("$fieldName must be between $min and $max.")
        }
        return value
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
