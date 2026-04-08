package com.github.mkubasz.oodclassicalautocompleted.infrastructure.startup

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.EditContextTracker
import com.github.mkubasz.oodclassicalautocompleted.editor.listeners.EditorSelectionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

class PluginStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().info("OOD Autocomplete plugin initialized for project: ${project.name}")
        val autocompleteService = project.service<AutocompleteService>()
        val editContextTracker = project.service<EditContextTracker>()
        val multicaster = EditorFactory.getInstance().eventMulticaster

        multicaster.addDocumentListener(object : DocumentListener {
            private var beforeText: String? = null

            override fun beforeDocumentChange(event: DocumentEvent) {
                beforeText = event.document.text
            }

            override fun documentChanged(event: DocumentEvent) {
                val editor = EditorFactory.getInstance()
                    .getEditors(event.document, project)
                    .firstOrNull { !it.isViewer }
                    ?: return

                // Feed edit context tracker
                val filePath = editor.virtualFile?.path
                if (filePath != null && beforeText != null) {
                    editContextTracker.onDocumentChanged(filePath, beforeText!!, event.document.text)
                }
                beforeText = null

                autocompleteService.onTyping(editor)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !editor.isDisposed) {
                        autocompleteService.requestCompletion(editor, editor.caretModel.offset)
                    }
                }
            }
        }, project)

        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                autocompleteService.onCaretMoved(event.editor)
            }
        }, project)

        multicaster.addSelectionListener(EditorSelectionListener(autocompleteService), project)

        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project != project || editor.isViewer) return

                // Track file as viewed when editor gains focus
                editor.contentComponent.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        val filePath = editor.virtualFile?.path ?: return
                        editContextTracker.onFileViewed(filePath, editor.document.text)
                    }

                    override fun focusLost(e: FocusEvent?) {
                        autocompleteService.onFocusLost(editor)
                    }
                })
            }
        }, project)
    }
}
