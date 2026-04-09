package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.flow.map

class OodInlineCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("ood-autocomplete-inline")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val settings = PluginSettings.getInstance()
        if (!settings.state.autocompleteEnabled || !settings.isConfigured) return false

        val request = event.toRequest() ?: return false
        val editor = request.editor
        if (editor.isViewer || editor.selectionModel.hasSelection()) return false

        if (TerminalDetector.isTerminalEditor(editor)) {
            return settings.state.terminalCompletionEnabled
        }
        return true
    }

    override fun restartOn(event: InlineCompletionEvent): Boolean = true

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val project = request.editor.project ?: return InlineCompletionSuggestion.Empty
        val service = project.service<AutocompleteService>()

        val suggestionFlow = service.fetchSuggestionFlow(request)
            ?: return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.Companion.build(
            UserDataHolderBase(),
            suggestionFlow.map { text -> InlineCompletionGrayTextElement(text) },
        )
    }
}
