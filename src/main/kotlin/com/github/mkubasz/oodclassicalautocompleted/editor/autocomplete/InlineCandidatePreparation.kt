package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate

internal object InlineCandidatePreparation {

    data class PreparationResult(
        val candidates: List<InlineCompletionCandidate>,
        val retryRequest: AutocompleteRequest? = null,
    )

    fun prepare(
        rawCandidates: List<InlineCompletionCandidate>,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
    ): List<InlineCompletionCandidate> = prepareWithDiagnostics(
        rawCandidates = rawCandidates,
        request = request,
        snapshot = snapshot,
        maxSuggestionChars = maxSuggestionChars,
    ).candidates

    fun prepareWithDiagnostics(
        rawCandidates: List<InlineCompletionCandidate>,
        request: AutocompleteRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
    ): PreparationResult {
        var retryRequest: AutocompleteRequest? = null

        val candidates = rawCandidates.mapNotNull { candidate ->
            val normalizedText = if (candidate.isExactInsertion) {
                candidate.text.trimEnd().take(maxSuggestionChars)
            } else {
                AutocompleteSuggestionNormalizer.normalize(
                    rawText = candidate.text,
                    request = request,
                    maxChars = maxSuggestionChars,
                )
            }

            val adjustedHeaderText = InlineHeaderCompletionAdjuster.adjust(normalizedText, request)
            if (adjustedHeaderText.isBlank()) return@mapNotNull null

            val adjustedCandidate = InlineSuggestionBoundaryAdjuster.adjust(
                candidate.copy(text = adjustedHeaderText),
                request,
            )
            if (!InlineSuggestionSafety.isSafe(adjustedCandidate, request)) return@mapNotNull null

            when (val validation = InlineHeaderPsiValidator.validate(adjustedCandidate, request, snapshot)) {
                InlineHeaderPsiValidator.Result.Valid -> adjustedCandidate
                InlineHeaderPsiValidator.Result.Invalid -> null
                is InlineHeaderPsiValidator.Result.Retryable -> {
                    if (retryRequest == null) {
                        retryRequest = buildRetryRequest(request, validation)
                    }
                    null
                }
            }
        }.distinctBy { it.insertionOffset to it.text }

        return PreparationResult(
            candidates = candidates,
            retryRequest = retryRequest.takeIf { candidates.isEmpty() },
        )
    }

    private fun buildRetryRequest(
        request: AutocompleteRequest,
        validation: InlineHeaderPsiValidator.Result.Retryable,
    ): AutocompleteRequest {
        val inlineContext = request.inlineContext ?: return request
        return request.copy(
            inlineContext = inlineContext.copy(
                headerValidationRetry = true,
                headerValidationError = validation.errorDescription,
                expectedHeaderContinuation = validation.expectedContinuation,
            )
        )
    }

    private const val DEFAULT_MAX_SUGGESTION_CHARS = 400
}
