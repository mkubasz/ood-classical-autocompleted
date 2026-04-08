package com.github.mkubasz.oodclassicalautocompleted.editor.actions

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class RejectCompletionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<AutocompleteService>().rejectSuggestion()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.service<AutocompleteService>().hasActiveSuggestion
    }
}
