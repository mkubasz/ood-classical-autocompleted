package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import java.util.Collections
import javax.swing.JComponent

@Service(Service.Level.APP)
class AutocompleteShortcutManager {

    private val editors = Collections.synchronizedSet(mutableSetOf<Editor>())

    fun registerEditor(editor: Editor) {
        if (editor.isDisposed || editor.isViewer) return
        editors.add(editor)
        applyToEditor(editor, PluginSettings.getInstance().state)
    }

    fun unregisterEditor(editor: Editor) {
        editors.remove(editor)
        unregisterFromEditor(editor)
    }

    fun applySettings(state: PluginSettings.State = PluginSettings.getInstance().state) {
        val actions = autocompleteActions()

        snapshotEditors().forEach { editor ->
            if (editor.isDisposed) {
                editors.remove(editor)
            } else {
                applyToEditor(editor, state, actions)
            }
        }
    }

    private fun applyToEditor(
        editor: Editor,
        state: PluginSettings.State,
        actions: AutocompleteActions = autocompleteActions(),
    ) {
        if (editor.isDisposed) return
        val component = editor.contentComponent

        actions.accept.unregisterCustomShortcutSet(component)
        actions.acceptInline.unregisterCustomShortcutSet(component)
        actions.acceptNextWord.unregisterCustomShortcutSet(component)
        actions.acceptNextLine.unregisterCustomShortcutSet(component)
        actions.reject.unregisterCustomShortcutSet(component)
        actions.cycleNext.unregisterCustomShortcutSet(component)
        actions.cyclePrevious.unregisterCustomShortcutSet(component)

        registerIfPresent(actions.accept, AutocompleteActionShortcuts.tabAcceptShortcutSet(), component)
        registerIfPresent(actions.acceptInline, AutocompleteActionShortcuts.inlineAcceptShortcutSet(state), component)
        registerIfPresent(actions.acceptNextWord, AutocompleteActionShortcuts.acceptNextWordShortcutSet(), component)
        registerIfPresent(actions.acceptNextLine, AutocompleteActionShortcuts.acceptNextLineShortcutSet(), component)
        registerIfPresent(actions.reject, AutocompleteActionShortcuts.rejectShortcutSet(), component)
        registerIfPresent(actions.cycleNext, AutocompleteActionShortcuts.cycleNextShortcutSet(state), component)
        registerIfPresent(actions.cyclePrevious, AutocompleteActionShortcuts.cyclePreviousShortcutSet(state), component)
    }

    private fun unregisterFromEditor(editor: Editor, actions: AutocompleteActions = autocompleteActions()) {
        if (editor.isDisposed) return
        val component = editor.contentComponent
        actions.accept.unregisterCustomShortcutSet(component)
        actions.acceptInline.unregisterCustomShortcutSet(component)
        actions.acceptNextWord.unregisterCustomShortcutSet(component)
        actions.acceptNextLine.unregisterCustomShortcutSet(component)
        actions.reject.unregisterCustomShortcutSet(component)
        actions.cycleNext.unregisterCustomShortcutSet(component)
        actions.cyclePrevious.unregisterCustomShortcutSet(component)
    }

    private fun registerIfPresent(action: AnAction, shortcuts: CustomShortcutSet, component: JComponent) {
        if (shortcuts.shortcuts.isNotEmpty()) {
            action.registerCustomShortcutSet(shortcuts, component)
        }
    }

    private fun autocompleteActions(): AutocompleteActions {
        val actionManager = ActionManager.getInstance()
        return AutocompleteActions(
            accept = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.ACCEPT_ACTION_ID)),
            acceptInline = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.ACCEPT_INLINE_ACTION_ID)),
            acceptNextWord = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.ACCEPT_NEXT_WORD_ACTION_ID)),
            acceptNextLine = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.ACCEPT_NEXT_LINE_ACTION_ID)),
            reject = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.REJECT_ACTION_ID)),
            cycleNext = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.CYCLE_NEXT_ACTION_ID)),
            cyclePrevious = requireNotNull(actionManager.getAction(AutocompleteActionShortcuts.CYCLE_PREVIOUS_ACTION_ID)),
        )
    }

    private fun snapshotEditors(): List<Editor> = synchronized(editors) { editors.toList() }

    private data class AutocompleteActions(
        val accept: AnAction,
        val acceptInline: AnAction,
        val acceptNextWord: AnAction,
        val acceptNextLine: AnAction,
        val reject: AnAction,
        val cycleNext: AnAction,
        val cyclePrevious: AnAction,
    )
}
