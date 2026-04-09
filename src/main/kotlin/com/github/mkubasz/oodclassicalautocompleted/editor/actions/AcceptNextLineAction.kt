package com.github.mkubasz.oodclassicalautocompleted.editor.actions

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AcceptNextLineAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        project.service<AutocompleteService>().acceptNextLine(editor)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = project != null &&
            editor != null &&
            project.service<AutocompleteService>().canAcceptInline(editor)
    }
}
