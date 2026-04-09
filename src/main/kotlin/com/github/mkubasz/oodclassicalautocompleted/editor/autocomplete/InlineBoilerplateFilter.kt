package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate

internal object InlineBoilerplateFilter {

    fun isAllowed(candidate: InlineCompletionCandidate, request: AutocompleteRequest): Boolean {
        val context = request.inlineContext ?: return true
        if (!isPython(request.language) || !context.isFreshBlockBodyContext) return true

        val normalized = candidate.text.trimStart().lowercase()
        return GENERIC_BODY_PHRASES.none { phrase -> phrase in normalized }
    }

    private fun isPython(language: String?): Boolean {
        val normalized = language.orEmpty().trim().lowercase()
        return normalized.contains("python") || normalized == "py"
    }

    private val GENERIC_BODY_PHRASES = listOf(
        "# example usage",
        "# additional logic",
        "can be added here",
        "example usage of the",
    )
}
