package com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest

internal object NextEditInlineAdapter {

    data class EditableRegion(
        val before: String,
        val text: String,
        val after: String,
        val startOffset: Int,
        val cursorOffset: Int,
    )

    data class DerivedEdit(
        val startOffset: Int,
        val endOffset: Int,
        val replacementText: String,
    )

    data class DerivedInsertion(
        val text: String,
        val offset: Int,
    )

    fun extractRegion(
        request: ProviderRequest,
        linesAboveCursor: Int,
        linesBelowCursor: Int,
    ): EditableRegion {
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
        val editStartLine = maxOf(0, cursorLine - linesAboveCursor)
        val editEndLineExclusive = minOf(lineStarts.size, cursorLine + linesBelowCursor + 1)
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

    fun deriveEdit(region: EditableRegion, updatedRegionText: String): DerivedEdit? {
        val original = region.text.replace("\r", "")
        val updated = updatedRegionText.replace("\r", "").trim('\n')
        if (updated == original) return null

        val commonPrefixLength = commonPrefixLength(original, updated)
        val commonSuffixLength = commonSuffixLength(
            original = original,
            updated = updated,
            prefixLength = commonPrefixLength,
        )

        val replacementStart = region.startOffset + commonPrefixLength
        val replacementEnd = region.startOffset + original.length - commonSuffixLength
        val replacementText = updated.substring(
            commonPrefixLength,
            updated.length - commonSuffixLength,
        )

        return DerivedEdit(
            startOffset = replacementStart,
            endOffset = replacementEnd,
            replacementText = replacementText,
        ).takeIf {
            replacementStart <= replacementEnd
        }
    }

    fun deriveInlineInsertion(region: EditableRegion, updatedRegionText: String): DerivedInsertion? {
        val original = region.text.replace("\r", "")
        val updated = updatedRegionText.replace("\r", "").trim('\n')
        if (updated == original) return null

        return getGhostTextOrNull(
            oldContent = original,
            newContent = updated,
            caretOffset = region.cursorOffset,
        )?.let { (text, insertionOffset) ->
            DerivedInsertion(
                text = text,
                offset = region.startOffset + insertionOffset,
            )
        }
    }

    private fun commonPrefixLength(original: String, updated: String): Int {
        val max = minOf(original.length, updated.length)
        var index = 0
        while (index < max && original[index] == updated[index]) {
            index++
        }
        return index
    }

    private fun commonSuffixLength(original: String, updated: String, prefixLength: Int): Int {
        val max = minOf(original.length, updated.length) - prefixLength
        var index = 0
        while (index < max &&
            original[original.length - 1 - index] == updated[updated.length - 1 - index]
        ) {
            index++
        }
        return index
    }

    private fun getGhostTextOrNull(
        oldContent: String,
        newContent: String,
        caretOffset: Int,
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
            if (insertedText.isNotEmpty() && '\n' !in insertedText) {
                return insertedText to insertionOffset
            }
        }

        return null
    }

    private const val CURSOR_MARKER = "<|cursor|>"
}
