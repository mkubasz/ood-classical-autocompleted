package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal data class GhostTextLayout(
    val inlineText: String,
    val blockLines: List<String>,
) {
    val blockText: String
        get() = blockLines.joinToString("\n")

    companion object {
        fun fromSuggestion(text: String): GhostTextLayout {
            val firstNewline = text.indexOf('\n')
            if (firstNewline < 0) {
                return GhostTextLayout(
                    inlineText = text,
                    blockLines = emptyList(),
                )
            }

            return GhostTextLayout(
                inlineText = text.substring(0, firstNewline),
                blockLines = splitLinesPreservingTrailingEmpty(text.substring(firstNewline + 1)),
            )
        }

        private fun splitLinesPreservingTrailingEmpty(text: String): List<String> {
            if (text.isEmpty()) return listOf("")

            val lines = mutableListOf<String>()
            var start = 0
            while (start <= text.length) {
                val newline = text.indexOf('\n', start)
                if (newline < 0) {
                    lines += text.substring(start)
                    break
                }
                lines += text.substring(start, newline)
                start = newline + 1
                if (start == text.length) {
                    lines += ""
                    break
                }
            }
            return lines
        }
    }
}
