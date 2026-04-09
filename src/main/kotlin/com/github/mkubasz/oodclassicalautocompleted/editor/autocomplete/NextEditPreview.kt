package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.NextEditCompletionCandidate
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

internal class NextEditPreview(
    private val editor: Editor,
    val candidate: NextEditCompletionCandidate,
) : Disposable {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()

    fun show() {
        val safeStart = candidate.startOffset.coerceIn(0, editor.document.textLength)
        val safeEnd = candidate.endOffset.coerceIn(safeStart, editor.document.textLength)

        editor.markupModel.addRangeHighlighter(
            safeStart,
            safeEnd,
            HighlighterLayer.SELECTION - 1,
            PREVIEW_ATTRIBUTES,
            HighlighterTargetArea.EXACT_RANGE,
        ).let(highlighters::add)

        val previewText = candidate.replacementText.ifEmpty { "<delete>" }
        editor.inlayModel
            .addBlockElement(
                safeStart,
                true,
                true,
                0,
                ReplacementBlockRenderer(previewText.lines().take(MAX_PREVIEW_LINES)),
            )
            ?.let(inlays::add)
    }

    fun jumpToPreview() {
        editor.caretModel.moveToOffset(candidate.startOffset.coerceIn(0, editor.document.textLength))
        if (editor is EditorEx) {
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }
    }

    fun apply(project: Project) {
        var appliedRange: TextRange? = null
        WriteCommandAction.runWriteCommandAction(project) {
            val safeStart = candidate.startOffset.coerceIn(0, editor.document.textLength)
            val safeEnd = candidate.endOffset.coerceIn(safeStart, editor.document.textLength)
            editor.document.replaceString(safeStart, safeEnd, candidate.replacementText)
            editor.caretModel.moveToOffset(safeStart + candidate.replacementText.length)
            appliedRange = TextRange(safeStart, safeStart + candidate.replacementText.length)
        }
        appliedRange?.let { NextEditImportActionResolver.applyBestEffort(project, editor, it) }
    }

    override fun dispose() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        inlays.forEach { it.dispose() }
        inlays.clear()
    }

    private class ReplacementBlockRenderer(
        private val lines: List<String>,
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val metrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC))
            return lines.maxOfOrNull { metrics.stringWidth(it) }?.coerceAtLeast(1) ?: 1
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            inlay.editor.lineHeight * lines.size.coerceAtLeast(1)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            g.color = PREVIEW_TEXT_COLOR
            g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

            lines.forEachIndexed { index, line ->
                val baseline = targetRegion.y + editor.ascent + index * editor.lineHeight
                g.drawString(line, targetRegion.x, baseline)
            }
        }
    }

    companion object {
        private const val MAX_PREVIEW_LINES = 20

        private val PREVIEW_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(
                Color(217, 255, 217),
                Color(39, 69, 39),
            )
        }

        private val PREVIEW_TEXT_COLOR = JBColor(
            Color(28, 102, 56),
            Color(143, 226, 170),
        )
    }
}
