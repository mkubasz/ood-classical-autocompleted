package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate

internal object InlineSuggestionBoundaryAdjuster {

    fun adjust(candidate: InlineCompletionCandidate, request: AutocompleteRequest): InlineCompletionCandidate {
        val adjustedText = if (
            needsLeadingNewline(
                text = candidate.text,
                request = request,
                insertionOffset = candidate.insertionOffset,
            )
        ) {
            "\n${candidate.text}"
        } else {
            candidate.text
        }
        return if (adjustedText == candidate.text) candidate else candidate.copy(text = adjustedText)
    }

    internal fun needsLeadingNewline(
        text: String,
        request: AutocompleteRequest,
        insertionOffset: Int,
    ): Boolean {
        if (text.isBlank() || text.startsWith('\n') || text.startsWith('\r')) return false

        val documentText = request.prefix + request.suffix
        val safeOffset = insertionOffset.coerceIn(0, documentText.length)
        val lineStart = documentText.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0))
            .let { if (it == -1) 0 else it + 1 }
        val lineEnd = documentText.indexOf('\n', safeOffset)
            .let { if (it == -1) documentText.length else it }

        val linePrefix = documentText.substring(lineStart, safeOffset)
        val lineSuffix = documentText.substring(safeOffset, lineEnd)
        if (lineSuffix.isNotBlank()) return false

        val trimmedPrefix = linePrefix.trimEnd()
        if (trimmedPrefix.isEmpty()) return false
        if (!startsFreshStatement(text)) return false
        if (!endsClosedStatement(trimmedPrefix)) return false

        return true
    }

    private fun startsFreshStatement(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return false

        return trimmed.startsWith("def ") ||
            trimmed.startsWith("class ") ||
            trimmed.startsWith("import ") ||
            trimmed.startsWith("from ") ||
            trimmed.startsWith("return ") ||
            trimmed.startsWith("raise ") ||
            trimmed.startsWith("pass") ||
            trimmed.startsWith("@") ||
            ASSIGNMENT_STATEMENT.matches(trimmed) ||
            CALL_STATEMENT.matches(trimmed)
    }

    private fun endsClosedStatement(linePrefix: String): Boolean {
        if (linePrefix.endsWith("->")) return false

        val last = linePrefix.last()
        if (last in SOFT_STATEMENT_ENDINGS) return false
        if (hasUnclosedGrouping(linePrefix)) return false

        val trailingToken = linePrefix.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        if (trailingToken in CONTINUATION_KEYWORDS) return false

        if (last in HARD_STATEMENT_ENDINGS) return true
        if (!last.isLetterOrDigit()) return false

        return precedingSignalBeforeTrailingToken(linePrefix, trailingToken) !in EXPRESSION_CONTINUATIONS
    }

    private fun precedingSignalBeforeTrailingToken(linePrefix: String, trailingToken: String): Char? {
        if (trailingToken.isEmpty()) return null

        val tokenStart = linePrefix.length - trailingToken.length
        var index = tokenStart - 1
        while (index >= 0 && linePrefix[index].isWhitespace()) {
            index--
        }
        return linePrefix.getOrNull(index)
    }

    private fun hasUnclosedGrouping(text: String): Boolean {
        var round = 0
        var square = 0
        var curly = 0

        text.forEach { ch ->
            when (ch) {
                '(' -> round++
                ')' -> round = (round - 1).coerceAtLeast(0)
                '[' -> square++
                ']' -> square = (square - 1).coerceAtLeast(0)
                '{' -> curly++
                '}' -> curly = (curly - 1).coerceAtLeast(0)
            }
        }

        return round > 0 || square > 0 || curly > 0
    }

    private val HARD_STATEMENT_ENDINGS = setOf(')', ']', '}', '\'', '"')
    private val SOFT_STATEMENT_ENDINGS = setOf('(', '[', '{', ',', '.', ':', '=', '\\')
    private val EXPRESSION_CONTINUATIONS = setOf('.', '(', '[', '{', ',', '+', '-', '*', '/', '%', '&', '|', '^', '<', '>')
    private val CONTINUATION_KEYWORDS = setOf(
        "and",
        "assert",
        "async",
        "await",
        "class",
        "def",
        "del",
        "elif",
        "except",
        "for",
        "from",
        "global",
        "if",
        "import",
        "in",
        "is",
        "lambda",
        "nonlocal",
        "not",
        "or",
        "raise",
        "return",
        "while",
        "with",
        "yield",
    )
    private val ASSIGNMENT_STATEMENT = Regex(
        """^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*\s*=.*""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val CALL_STATEMENT = Regex(
        """^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*\(.*""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
}
