package com.github.mkubasz.oodclassicalautocompleted.infrastructure.startup

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.EditContextTracker
import com.github.mkubasz.oodclassicalautocompleted.editor.listeners.EditorSelectionListener
import com.github.mkubasz.oodclassicalautocompleted.settings.AutocompleteShortcutManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Editor
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
        val shortcutManager = ApplicationManager.getApplication().getService(AutocompleteShortcutManager::class.java)
        shortcutManager.applySettings()
        val autocompleteService = project.service<AutocompleteService>()
        val editContextTracker = project.service<EditContextTracker>()
        val editorFactory = EditorFactory.getInstance()
        val multicaster = editorFactory.eventMulticaster

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

                autocompleteService.onDocumentChanged(
                    editor = editor,
                    changeOffset = event.offset,
                    oldLength = event.oldLength,
                    newText = event.newFragment.toString(),
                )
            }
        }, project)

        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                autocompleteService.onCaretMoved(event.editor)
            }
        }, project)

        multicaster.addSelectionListener(EditorSelectionListener(autocompleteService), project)

        fun configureEditor(editor: Editor) {
            if (editor.project != project || editor.isViewer) return
            val setup = fun() {
                if (project.isDisposed || editor.isDisposed || editor.project != project || editor.isViewer) {
                    return
                }

                shortcutManager.registerEditor(editor)
                autocompleteService.installInlineSupport(editor)

                // Track file as viewed when editor gains focus
                editor.contentComponent.addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent?) {
                        val filePath = editor.virtualFile?.path ?: return
                        editContextTracker.onFileViewed(
                            filePath = filePath,
                            content = editor.document.text,
                            caretOffset = editor.caretModel.offset,
                        )
                    }

                    override fun focusLost(e: FocusEvent?) {
                        autocompleteService.onFocusLost(editor)
                    }
                })
            }

            if (ApplicationManager.getApplication().isDispatchThread) {
                setup()
            } else {
                ApplicationManager.getApplication().invokeLater(
                    {
                        setup()
                    },
                    project.disposed
                )
            }
        }

        editorFactory.allEditors
            .filter { it.project == project && !it.isViewer }
            .forEach(::configureEditor)

        editorFactory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                configureEditor(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                shortcutManager.unregisterEditor(event.editor)
                autocompleteService.unregisterEditor(event.editor)
            }
        }, project)
    }
}
