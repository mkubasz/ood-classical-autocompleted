package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.settings.PluginSettings
import com.intellij.openapi.application.ApplicationManager

internal object InlineCandidatePreparation {

    data class Options(
        val minConfidenceScore: Double = 0.0,
        val correctnessFilterEnabled: Boolean = false,
        val onCorrectnessResult: ((InlineCorrectnessFilter.Result) -> Unit)? = null,
    ) {
        companion object {
            fun fromSettings(): Options {
                val application = ApplicationManager.getApplication() ?: return Options()
                val settings = application.getService(PluginSettings::class.java)?.state ?: return Options()
                return Options(
                    minConfidenceScore = settings.minConfidenceScore,
                    correctnessFilterEnabled = settings.correctnessFilterEnabled,
                )
            }
        }
    }

    data class PreparationResult(
        val candidates: List<InlineCompletionCandidate>,
        val retryRequest: ProviderRequest? = null,
    )

    fun prepare(
        rawCandidates: List<InlineCompletionCandidate>,
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
    ): List<InlineCompletionCandidate> = prepare(
        rawCandidates = rawCandidates,
        request = request,
        snapshot = snapshot,
        maxSuggestionChars = maxSuggestionChars,
        options = Options.fromSettings(),
    )

    fun prepare(
        rawCandidates: List<InlineCompletionCandidate>,
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
        options: Options,
    ): List<InlineCompletionCandidate> = prepareWithDiagnostics(
        rawCandidates = rawCandidates,
        request = request,
        snapshot = snapshot,
        maxSuggestionChars = maxSuggestionChars,
        options = options,
    ).candidates

    fun prepareWithDiagnostics(
        rawCandidates: List<InlineCompletionCandidate>,
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
    ): PreparationResult = prepareWithDiagnostics(
        rawCandidates = rawCandidates,
        request = request,
        snapshot = snapshot,
        maxSuggestionChars = maxSuggestionChars,
        options = Options.fromSettings(),
    )

    fun prepareWithDiagnostics(
        rawCandidates: List<InlineCompletionCandidate>,
        request: ProviderRequest,
        snapshot: CompletionContextSnapshot? = null,
        maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
        options: Options,
    ): PreparationResult {
        var retryRequest: ProviderRequest? = null

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
        }
            .filter { candidate -> InlineBoilerplateFilter.isAllowed(candidate, request) }
            .distinctBy { it.insertionOffset to it.text }
            .map { candidate ->
                val score = InlineConfidenceScorer.score(candidate, request)
                candidate.copy(confidenceScore = score)
            }
            .filter { it.confidenceScore == null || it.confidenceScore >= options.minConfidenceScore }
            .let { scored ->
                if (!options.correctnessFilterEnabled || snapshot == null) return@let scored
                scored.filter { candidate ->
                    val result = InlineCorrectnessFilter.check(candidate, request, snapshot)
                    options.onCorrectnessResult?.invoke(result)
                    result !is InlineCorrectnessFilter.Result.Reject
                }
            }

        return PreparationResult(
            candidates = candidates,
            retryRequest = retryRequest.takeIf { candidates.isEmpty() },
        )
    }

    private fun buildRetryRequest(
        request: ProviderRequest,
        validation: InlineHeaderPsiValidator.Result.Retryable,
    ): ProviderRequest {
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
