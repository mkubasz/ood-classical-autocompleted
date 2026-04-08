package com.github.mkubasz.oodclassicalautocompleted.editor.listeners

import com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete.AutocompleteService
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener

class EditorSelectionListener(
    private val autocompleteService: AutocompleteService,
) : SelectionListener {

    override fun selectionChanged(e: SelectionEvent) {
        autocompleteService.onSelectionChanged(e.editor)
    }
}
