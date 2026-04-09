package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
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
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

internal class NextEditPreview(
    private val editor: Editor,
    val candidate: NextEditCompletionCandidate,
    private val maxPreviewLines: Int,
) : Disposable {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()

    fun show() {
        val safeStart = candidate.startOffset.coerceIn(0, editor.document.textLength)
        val safeEnd = candidate.endOffset.coerceIn(safeStart, editor.document.textLength)
        val originalText = editor.document.getText(TextRange(safeStart, safeEnd))

        editor.markupModel.addRangeHighlighter(
            safeStart,
            safeEnd,
            HighlighterLayer.SELECTION - 1,
            PREVIEW_ATTRIBUTES,
            HighlighterTargetArea.EXACT_RANGE,
        ).let(highlighters::add)

        val lines = buildPreviewLines(originalText, candidate.replacementText)
        if (lines.isEmpty()) return

        editor.inlayModel
            .addBlockElement(
                safeStart,
                true,
                true,
                0,
                DiffBlockRenderer(lines),
            )
            ?.let(inlays::add)
    }

    fun jumpToPreview() {
        editor.caretModel.moveToOffset(candidate.startOffset.coerceIn(0, editor.document.textLength))
        if (editor is EditorEx) {
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }

    fun apply(project: Project): NextEditPostApplyResult {
        var appliedRange: TextRange? = null
        WriteCommandAction.runWriteCommandAction(project) {
            val safeStart = candidate.startOffset.coerceIn(0, editor.document.textLength)
            val safeEnd = candidate.endOffset.coerceIn(safeStart, editor.document.textLength)
            editor.document.replaceString(safeStart, safeEnd, candidate.replacementText)
            editor.caretModel.moveToOffset(safeStart + candidate.replacementText.length)
            appliedRange = TextRange(safeStart, safeStart + candidate.replacementText.length)
        }
        return appliedRange?.let { NextEditPostApplyProcessor.applyBestEffort(project, editor, it) }
            ?: NextEditPostApplyResult()
    }

    override fun dispose() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
        inlays.forEach { it.dispose() }
        inlays.clear()
    }

    companion object {
        private val PREVIEW_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(
                Color(235, 245, 255),
                Color(45, 57, 72),
            )
        }

        private val HEADER_TEXT = JBColor(
            Color(49, 58, 73),
            Color(220, 224, 229),
        )
        private val DEFAULT_TEXT = JBColor(
            Color(49, 58, 73),
            Color(220, 224, 229),
        )
        private val REMOVAL_TEXT = JBColor(
            Color(138, 28, 36),
            Color(255, 180, 185),
        )
        private val REMOVAL_BACKGROUND = JBColor(
            Color(255, 236, 238),
            Color(77, 38, 44),
        )
        private val ADDITION_TEXT = JBColor(
            Color(25, 110, 69),
            Color(175, 243, 198),
        )
        private val ADDITION_BACKGROUND = JBColor(
            Color(232, 252, 240),
            Color(34, 67, 48),
        )
        private const val PREVIEW_PADDING_X = 6
        private const val PREVIEW_PADDING_Y = 4
        private const val PREVIEW_MIN_LINES = 2
    }

    private fun buildPreviewLines(originalText: String, replacementText: String): List<PreviewLine> {
        val lines = mutableListOf(
            PreviewLine(
                text = "@@ next-edit preview @@",
                foreground = HEADER_TEXT,
                background = null,
                bold = true,
            )
        )

        if (originalText.isNotEmpty()) {
            originalText.lines().forEach { line ->
                lines += PreviewLine(
                    text = "-$line",
                    foreground = REMOVAL_TEXT,
                    background = REMOVAL_BACKGROUND,
                )
            }
        }

        if (replacementText.isNotEmpty()) {
            replacementText.lines().forEach { line ->
                lines += PreviewLine(
                    text = "+$line",
                    foreground = ADDITION_TEXT,
                    background = ADDITION_BACKGROUND,
                )
            }
        }

        if (originalText.isEmpty() && replacementText.isEmpty()) {
            lines += PreviewLine(
                text = "(no changes)",
                foreground = DEFAULT_TEXT,
                background = null,
            )
        }

        val effectiveLimit = maxPreviewLines.coerceAtLeast(PREVIEW_MIN_LINES)
        if (lines.size <= effectiveLimit) return lines
        return lines.take(effectiveLimit - 1) + PreviewLine(
            text = "...",
            foreground = DEFAULT_TEXT,
            background = null,
        )
    }

    private data class PreviewLine(
        val text: String,
        val foreground: Color,
        val background: Color?,
        val bold: Boolean = false,
    )

    private class DiffBlockRenderer(
        private val lines: List<PreviewLine>,
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val plainMetrics = editor.contentComponent.getFontMetrics(font(editor, bold = false))
            val boldMetrics = editor.contentComponent.getFontMetrics(font(editor, bold = true))
            val maxWidth = lines.maxOfOrNull { line ->
                val metrics = if (line.bold) boldMetrics else plainMetrics
                metrics.stringWidth(line.text)
            } ?: 1
            return maxWidth + PREVIEW_PADDING_X * 2
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            inlay.editor.lineHeight * lines.size.coerceAtLeast(1) + PREVIEW_PADDING_Y * 2

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val plainFont = font(editor, bold = false)
            val boldFont = font(editor, bold = true)

            lines.forEachIndexed { index, line ->
                val lineY = targetRegion.y + PREVIEW_PADDING_Y + index * editor.lineHeight
                line.background?.let { background ->
                    g.color = background
                    g.fillRect(targetRegion.x, lineY, targetRegion.width, editor.lineHeight)
                }

                g.color = line.foreground
                g.font = if (line.bold) boldFont else plainFont
                val baseline = lineY + editor.ascent
                g.drawString(line.text, targetRegion.x + PREVIEW_PADDING_X, baseline)
            }
        }

        private fun font(editor: Editor, bold: Boolean): Font =
            editor.colorsScheme.getFont(if (bold) EditorFontType.BOLD else EditorFontType.PLAIN)
    }
}
