package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object ContextBudgetPacker {

    data class Budget(
        val totalChars: Int = 4_000,
        val minPrefixChars: Int = 800,
        val minSuffixChars: Int = 400,
        val maxSemanticChars: Int = 1_200,
    )

    data class PackedContext(
        val semanticPrefix: String,
        val localPrefix: String,
        val localSuffix: String,
    )

    fun pack(
        semanticContext: String,
        fullPrefix: String,
        fullSuffix: String,
        budget: Budget,
    ): PackedContext {
        val semanticChars = semanticContext.length.coerceAtMost(budget.maxSemanticChars)
        val remaining = (budget.totalChars - semanticChars).coerceAtLeast(
            budget.minPrefixChars + budget.minSuffixChars
        )
        val prefixShare = (remaining * 3 / 5).coerceAtLeast(budget.minPrefixChars)
        val suffixShare = (remaining - prefixShare).coerceAtLeast(budget.minSuffixChars)

        return PackedContext(
            semanticPrefix = semanticContext.take(semanticChars),
            localPrefix = fullPrefix.takeLast(prefixShare),
            localSuffix = fullSuffix.take(suffixShare),
        )
    }
}
