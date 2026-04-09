package com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared

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

    fun anthropicBudget(totalChars: Int): Budget =
        scaledBudget(
            totalChars = totalChars,
            referenceTotalChars = 3_500,
            referenceMinPrefixChars = 800,
            referenceMinSuffixChars = 500,
            referenceMaxSemanticChars = 800,
        )

    fun inceptionFimBudget(totalChars: Int): Budget =
        scaledBudget(
            totalChars = totalChars,
            referenceTotalChars = 4_000,
            referenceMinPrefixChars = 1_200,
            referenceMinSuffixChars = 600,
            referenceMaxSemanticChars = 1_000,
        )

    private fun scaledBudget(
        totalChars: Int,
        referenceTotalChars: Int,
        referenceMinPrefixChars: Int,
        referenceMinSuffixChars: Int,
        referenceMaxSemanticChars: Int,
    ): Budget {
        val safeTotal = totalChars.coerceAtLeast(1_200)
        val scale = safeTotal.toDouble() / referenceTotalChars.toDouble()
        return Budget(
            totalChars = safeTotal,
            minPrefixChars = (referenceMinPrefixChars * scale).toInt().coerceAtLeast(300),
            minSuffixChars = (referenceMinSuffixChars * scale).toInt().coerceAtLeast(150),
            maxSemanticChars = (referenceMaxSemanticChars * scale).toInt().coerceAtLeast(250),
        )
    }
}
