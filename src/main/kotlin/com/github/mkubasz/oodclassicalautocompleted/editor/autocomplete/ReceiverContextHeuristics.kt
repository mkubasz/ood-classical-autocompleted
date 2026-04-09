package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal object ReceiverContextHeuristics {

    fun extractReceiverExpression(currentLinePrefix: String): String? {
        val trimmed = currentLinePrefix.trimEnd()
        if (!trimmed.endsWith('.')) return null

        val receiver = trimmed
            .dropLast(1)
            .takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
            .trim('.')

        if (receiver.isBlank()) return null
        return receiver
    }

    fun isSelfLike(receiverExpression: String): Boolean =
        receiverExpression == "self" || receiverExpression == "this"
}
