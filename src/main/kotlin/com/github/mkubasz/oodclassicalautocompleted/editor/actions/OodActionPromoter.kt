package com.github.mkubasz.oodclassicalautocompleted.editor.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

class OodActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        // Promote autocomplete accept/reject above IDE defaults
        // so Tab/Escape are intercepted when autocomplete is active
        val oodActions = actions.filter {
            it is AcceptCompletionAction ||
                it is AcceptInlineSuggestionAction ||
                it is AcceptNextWordAction ||
                it is AcceptNextLineAction ||
                it is RejectCompletionAction ||
                it is CycleNextSuggestionAction ||
                it is CyclePreviousSuggestionAction
        }
        if (oodActions.isEmpty()) return emptyList()
        return oodActions + actions.filter { it !in oodActions }
    }
}
