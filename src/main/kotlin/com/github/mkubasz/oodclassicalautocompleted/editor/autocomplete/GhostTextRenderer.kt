package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

class GhostTextRenderer(
    val editor: Editor,
    val anchorOffset: Int,
    val insertionOffset: Int,
    val suggestionText: String,
) : Disposable {

    private val inlays = mutableListOf<Inlay<*>>()

    val isActive: Boolean get() = inlays.any { it.isValid }

    val suggestionKey: RejectionCache.SuggestionKey
        get() = RejectionCache.SuggestionKey(
            editor.document.text.hashCode(),
            anchorOffset,
            31 * insertionOffset + suggestionText.hashCode(),
        )

    fun show() {
        val layout = GhostTextLayout.fromSuggestion(suggestionText)

        if (layout.inlineText.isNotEmpty()) {
            editor.inlayModel
                .addInlineElement(insertionOffset, true, InlineGhostRenderer(layout.inlineText))
                ?.let(inlays::add)
        }

        if (layout.blockLines.isNotEmpty()) {
            editor.inlayModel
                .addBlockElement(insertionOffset, true, false, 0, BlockGhostRenderer(layout.blockLines))
                ?.let(inlays::add)
        }
    }

    fun apply(project: Project, editor: Editor) {
        dispose()

        WriteCommandAction.runWriteCommandAction(project) {
            if (insertionOffset > editor.document.textLength) return@runWriteCommandAction
            editor.document.insertString(insertionOffset, suggestionText)
            val updatedCaretOffset = if (editor.caretModel.offset >= insertionOffset) {
                editor.caretModel.offset + suggestionText.length
            } else {
                editor.caretModel.offset
            }
            editor.caretModel.moveToOffset(updatedCaretOffset)
        }
    }

    override fun dispose() {
        inlays.forEach { it.dispose() }
        inlays.clear()
    }

    private class InlineGhostRenderer(private val text: String) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val fontMetrics = editor.contentComponent.getFontMetrics(
                editor.colorsScheme.getFont(EditorFontType.ITALIC)
            )
            return fontMetrics.stringWidth(text)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            g.color = GHOST_TEXT_COLOR
            g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            g.drawString(text, targetRegion.x, targetRegion.y + editor.ascent)
        }
    }

    private class BlockGhostRenderer(private val lines: List<String>) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val fontMetrics = editor.contentComponent.getFontMetrics(
                editor.colorsScheme.getFont(EditorFontType.ITALIC)
            )
            return lines.maxOfOrNull { fontMetrics.stringWidth(it) }?.coerceAtLeast(1) ?: 1
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            inlay.editor.lineHeight * lines.size.coerceAtLeast(1)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            g.color = GHOST_TEXT_COLOR
            val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            g.font = font
            val fontMetrics = editor.contentComponent.getFontMetrics(font)
            val spaceWidth = fontMetrics.charWidth(' ')

            lines.forEachIndexed { index, line ->
                val baseline = targetRegion.y + editor.ascent + index * editor.lineHeight
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                val indentWidth = if (indent.isEmpty()) 0 else {
                    indent.sumOf { ch ->
                        if (ch == '\t') spaceWidth * editor.settings.getTabSize(editor.project)
                        else spaceWidth
                    }
                }
                g.drawString(line.trimStart(), indentWidth, baseline)
            }
        }
    }

    companion object {
        private val GHOST_TEXT_COLOR = JBColor(
            Color(128, 128, 128, 160),
            Color(180, 180, 180, 140),
        )
    }
}
