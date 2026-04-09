package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.editor.Editor

/**
 * Detects whether an editor is a terminal prompt editor.
 * Terminal prompt editors have specific characteristics:
 * - Their virtual file is null or has a terminal-specific type
 * - The document is typically short (single command line)
 * - The editor component hierarchy contains terminal-related classes
 */
internal object TerminalDetector {

    fun isTerminalEditor(editor: Editor): Boolean {
        val virtualFile = editor.virtualFile
        if (virtualFile != null) {
            val fileTypeName = virtualFile.fileType.name
            if (fileTypeName.contains("Terminal", ignoreCase = true)) return true
        }

        val componentHierarchy = generateSequence(editor.contentComponent.parent) { it.parent }
            .take(MAX_HIERARCHY_DEPTH)
        if (componentHierarchy.any { it.javaClass.name.contains("Terminal", ignoreCase = true) }) {
            return true
        }

        return false
    }

    private const val MAX_HIERARCHY_DEPTH = 8
}
