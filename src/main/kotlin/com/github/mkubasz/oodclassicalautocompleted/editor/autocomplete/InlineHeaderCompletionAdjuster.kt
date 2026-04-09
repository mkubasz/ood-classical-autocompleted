package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest

internal object InlineHeaderCompletionAdjuster {

    fun adjust(text: String, request: AutocompleteRequest): String {
        if (text.isBlank()) return text
        if (!isPythonHeaderContext(request)) return text

        return sanitizePythonHeaderContinuation(
            linePrefix = request.prefix.substringAfterLast('\n'),
            lineSuffix = request.suffix.substringBefore('\n'),
            candidateText = text,
        )
    }

    private fun isPythonHeaderContext(request: AutocompleteRequest): Boolean {
        val language = request.language.orEmpty().lowercase()
        val context = request.inlineContext

        if (!language.contains("py") && !language.contains("python")) return false
        return context?.isDefinitionHeaderLikeContext == true ||
            context?.isClassBaseListLikeContext == true
    }

    private fun sanitizePythonHeaderContinuation(
        linePrefix: String,
        lineSuffix: String,
        candidateText: String,
    ): String {
        var roundBalance = unmatchedRoundParens(linePrefix)
        for (ch in lineSuffix.trimStart()) {
            if (ch == ')' && roundBalance > 0) roundBalance--
            else if (!ch.isWhitespace()) break
        }
        var seenColon = false
        var seenComment = false
        val builder = StringBuilder(candidateText.length)

        candidateText.forEach { ch ->
            when {
                seenComment -> builder.append(ch)
                ch == '\n' || ch == '\r' -> builder.append(ch)
                !seenColon && ch == '(' -> {
                    roundBalance++
                    builder.append(ch)
                }
                !seenColon && ch == ')' -> {
                    if (roundBalance > 0) {
                        roundBalance--
                        builder.append(ch)
                    }
                }
                !seenColon && ch == ':' -> {
                    seenColon = true
                    builder.append(ch)
                }
                !seenColon -> builder.append(ch)
                ch == '#' -> {
                    seenComment = true
                    builder.append(ch)
                }
                ch in EXTRA_CLOSERS_AFTER_COLON -> Unit
                ch == ':' && builder.lastOrNull() == ':' -> Unit
                else -> builder.append(ch)
            }
        }

        return builder.toString()
    }

    private fun unmatchedRoundParens(text: String): Int {
        var balance = 0
        text.forEach { ch ->
            when (ch) {
                '(' -> balance++
                ')' -> balance = (balance - 1).coerceAtLeast(0)
            }
        }
        return balance
    }

    private val EXTRA_CLOSERS_AFTER_COLON = setOf(')', ']', '}')
}
