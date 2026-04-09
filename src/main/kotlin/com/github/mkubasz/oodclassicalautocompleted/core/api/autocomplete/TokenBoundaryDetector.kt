package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object TokenBoundaryDetector {

    data class HealedSplit(
        val prefix: String,
        val healedPrefix: String,
        val suffix: String,
    )

    fun heal(prefix: String, suffix: String): HealedSplit {
        if (prefix.isEmpty() || suffix.isEmpty()) {
            return HealedSplit(prefix = prefix, healedPrefix = "", suffix = suffix)
        }

        val lastChar = prefix.last()
        val firstChar = suffix.first()

        if (!isMidToken(lastChar, firstChar)) {
            return HealedSplit(prefix = prefix, healedPrefix = "", suffix = suffix)
        }

        val tokenStart = prefix.indexOfLast { !it.isLetterOrDigit() && it != '_' } + 1
        val partialToken = prefix.substring(tokenStart)
        val adjustedPrefix = prefix.substring(0, tokenStart)

        return HealedSplit(
            prefix = adjustedPrefix,
            healedPrefix = partialToken,
            suffix = suffix,
        )
    }

    private fun isMidToken(lastChar: Char, firstChar: Char): Boolean =
        (lastChar.isLetterOrDigit() || lastChar == '_') &&
            (firstChar.isLetterOrDigit() || firstChar == '_')
}
