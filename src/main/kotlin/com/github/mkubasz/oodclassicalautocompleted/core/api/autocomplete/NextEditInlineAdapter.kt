package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object NextEditInlineAdapter {

    data class EditableRegion(
        val before: String,
        val text: String,
        val after: String,
        val startOffset: Int,
        val cursorOffset: Int,
    )

    data class DerivedInsertion(
        val text: String,
        val offset: Int,
    )

    fun extractRegion(request: AutocompleteRequest, radius: Int): EditableRegion {
        val fullText = request.prefix + request.suffix
        val cursorOffset = request.cursorOffset ?: request.prefix.length
        val lineStarts = mutableListOf(0)
        fullText.forEachIndexed { index, ch ->
            if (ch == '\n' && index + 1 <= fullText.length) {
                lineStarts += index + 1
            }
        }

        val cursorLine = when (val exactLine = lineStarts.indexOf(cursorOffset)) {
            -1 -> lineStarts.indexOfLast { it <= cursorOffset }.coerceAtLeast(0)
            else -> exactLine
        }
        val editStartLine = maxOf(0, cursorLine - radius)
        val editEndLineExclusive = minOf(lineStarts.size, cursorLine + radius + 1)
        val regionStartOffset = lineStarts[editStartLine]
        val regionEndOffset = if (editEndLineExclusive < lineStarts.size) {
            lineStarts[editEndLineExclusive] - 1
        } else {
            fullText.length
        }
        val regionText = fullText.substring(regionStartOffset, regionEndOffset)

        return EditableRegion(
            before = fullText.substring(0, regionStartOffset),
            text = regionText,
            after = fullText.substring(regionEndOffset),
            startOffset = regionStartOffset,
            cursorOffset = (cursorOffset - regionStartOffset).coerceIn(0, regionText.length),
        )
    }

    fun renderWithCursor(region: EditableRegion): String =
        region.text.substring(0, region.cursorOffset) +
            CURSOR_MARKER +
            region.text.substring(region.cursorOffset)

    fun deriveInsertion(region: EditableRegion, updatedRegionText: String): DerivedInsertion? {
        val original = region.text.replace("\r", "")
        val updated = updatedRegionText.replace("\r", "").trim('\n')
        if (updated == original) {
            return DerivedInsertion("", region.startOffset + region.cursorOffset)
        }

        return getGhostTextOrNull(
            oldContent = original,
            newContent = updated,
            caretOffset = region.cursorOffset,
            atEndOfDocument = region.after.isEmpty(),
        )?.let { (text, insertionOffset) ->
            DerivedInsertion(
                text = text,
                offset = region.startOffset + insertionOffset,
            )
        }
    }

    private fun getGhostTextOrNull(
        oldContent: String,
        newContent: String,
        caretOffset: Int,
        atEndOfDocument: Boolean,
    ): Pair<String, Int>? {
        val caretInSpan = caretOffset in 0 until oldContent.length
        if (caretInSpan) {
            val prefix = oldContent.take(caretOffset)
            val suffix = oldContent.drop(caretOffset)
            val containsCaretPrefixAndSuffix = newContent.startsWith(prefix) && newContent.endsWith(suffix)
            val isLonger = newContent.length > prefix.length + suffix.length
            if (containsCaretPrefixAndSuffix && isLonger) {
                val insertedText = newContent.substring(prefix.length, newContent.length - suffix.length)
                if (insertedText.isNotEmpty() && '\n' !in insertedText) {
                    return insertedText to caretOffset
                }
            }
        }

        for (insertionOffset in oldContent.length downTo 0) {
            val prefix = oldContent.take(insertionOffset)
            val suffix = oldContent.drop(insertionOffset)
            if (!newContent.startsWith(prefix) || !newContent.endsWith(suffix)) continue
            if (prefix.length + suffix.length > newContent.length) continue

            val insertedText = newContent.substring(prefix.length, newContent.length - suffix.length)
            val caretAtNewline = prefix.isEmpty() || prefix.last() == '\n'
            if ('\n' in insertedText && !caretAtNewline && !atEndOfDocument) {
                return null
            }
            if (insertedText.isNotEmpty()) {
                return insertedText to insertionOffset
            }
        }

        return null
    }

    private const val CURSOR_MARKER = "<|cursor|>"
}
