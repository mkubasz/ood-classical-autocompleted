package com.github.mkubasz.oodclassicalautocompleted.editor.actions

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AcceptCompletionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        project.service<AutocompleteService>().acceptSuggestion(editor)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            project.service<AutocompleteService>().hasActiveSuggestion
    }
}
