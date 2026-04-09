package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProvider
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.CompletionResponse
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineCompletionCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

internal object AutocompleteProviderCoordinator {

    suspend fun complete(
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider?,
        nextEditProvider: AutocompleteProvider?,
    ): CompletionResponse? = when {
        fimProvider != null && nextEditProvider != null -> completeWithParallelFallback(
            request = request,
            fimProvider = fimProvider,
            nextEditProvider = nextEditProvider,
        )

        fimProvider != null -> fimProvider.complete(request)
        nextEditProvider != null -> nextEditProvider.complete(request)
        else -> null
    }

    private suspend fun completeWithParallelFallback(
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider,
        nextEditProvider: AutocompleteProvider,
    ): CompletionResponse? = coroutineScope {
        val fimDeferred = async { fimProvider.complete(request) }
        val nextEditDeferred = async { nextEditProvider.complete(request) }

        repeat(2) {
            when (val completed = select<CompletedResult> {
                if (fimDeferred.isActive) {
                    fimDeferred.onAwait { CompletedResult.Fim(it) }
                }
                if (nextEditDeferred.isActive) {
                    nextEditDeferred.onAwait { CompletedResult.NextEdit(it) }
                }
            }) {
                is CompletedResult.Fim -> {
                    if (completed.result != null) {
                        nextEditProvider.cancel()
                        nextEditDeferred.cancel()
                        return@coroutineScope completed.result
                    }
                }

                is CompletedResult.NextEdit -> {
                    if (completed.result != null) {
                        fimProvider.cancel()
                        fimDeferred.cancel()
                        return@coroutineScope completed.result
                    }
                }
            }
        }

        null
    }

    suspend fun selectInlineCandidates(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider?,
        nextEditProvider: AutocompleteProvider?,
        onProviderFailure: (String, Exception) -> Unit = { _, _ -> },
        shouldShow: (InlineCompletionCandidate) -> Boolean = { true },
    ): InlineSelectionResult? = when {
        fimProvider != null && nextEditProvider != null -> selectParallelInlineCandidates(
            snapshot = snapshot,
            request = request,
            fimProvider = fimProvider,
            nextEditProvider = nextEditProvider,
            onProviderFailure = onProviderFailure,
            shouldShow = shouldShow,
        )

        fimProvider != null -> selectInlineCandidatesFromProvider(
            snapshot = snapshot,
            request = request,
            provider = fimProvider,
            sourceName = "fim",
            onProviderFailure = onProviderFailure,
            shouldShow = shouldShow,
        )

        nextEditProvider != null -> selectInlineCandidatesFromProvider(
            snapshot = snapshot,
            request = request,
            provider = nextEditProvider,
            sourceName = "next_edit",
            onProviderFailure = onProviderFailure,
            shouldShow = shouldShow,
        )

        else -> null
    }

    fun firstValidInlineSelection(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        responsesInCompletionOrder: List<ProviderInlineResponse>,
        retryResponsesBySource: Map<String, ProviderInlineResponse> = emptyMap(),
        shouldShow: (InlineCompletionCandidate) -> Boolean = { true },
    ): InlineSelectionResult? = responsesInCompletionOrder.firstNotNullOfOrNull { response ->
        val computation = selectionComputationFromResponse(
            snapshot = snapshot,
            request = request,
            sourceName = response.sourceName,
            response = response.response,
            shouldShow = shouldShow,
        )
        computation.selection ?: retryResponsesBySource[response.sourceName]
            ?.takeIf { response.sourceName == FIM_SOURCE }
            ?.let { retryResponse ->
                computation.retryRequest?.let { retryRequest ->
                    selectionComputationFromResponse(
                        snapshot = snapshot,
                        request = retryRequest,
                        sourceName = "${response.sourceName}_retry",
                        response = retryResponse.response,
                        shouldShow = shouldShow,
                    ).selection
                }
            }
    }

    private suspend fun selectParallelInlineCandidates(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider,
        nextEditProvider: AutocompleteProvider,
        onProviderFailure: (String, Exception) -> Unit,
        shouldShow: (InlineCompletionCandidate) -> Boolean,
    ): InlineSelectionResult? = coroutineScope {
        val fimDeferred = async { requestProviderResponse(fimProvider, request, "fim", onProviderFailure) }
        val nextEditDeferred = async {
            requestProviderResponse(nextEditProvider, request, "next_edit", onProviderFailure)
        }

        repeat(2) {
            when (val completed = select<CompletedResult> {
                if (fimDeferred.isActive) {
                    fimDeferred.onAwait { CompletedResult.Fim(it) }
                }
                if (nextEditDeferred.isActive) {
                    nextEditDeferred.onAwait { CompletedResult.NextEdit(it) }
                }
            }) {
                is CompletedResult.Fim -> {
                    val selection = selectionFromResponseWithRetry(
                        snapshot = snapshot,
                        request = request,
                        sourceName = FIM_SOURCE,
                        response = completed.result,
                        provider = fimProvider,
                        onProviderFailure = onProviderFailure,
                        shouldShow = shouldShow,
                    )
                    if (selection != null) {
                        nextEditProvider.cancel()
                        nextEditDeferred.cancel()
                        return@coroutineScope selection
                    }
                }

                is CompletedResult.NextEdit -> {
                    val selection = selectionComputationFromResponse(
                        snapshot = snapshot,
                        request = request,
                        sourceName = "next_edit",
                        response = completed.result,
                        shouldShow = shouldShow,
                    ).selection
                    if (selection != null) {
                        fimProvider.cancel()
                        fimDeferred.cancel()
                        return@coroutineScope selection
                    }
                }
            }
        }

        null
    }

    private suspend fun selectInlineCandidatesFromProvider(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        provider: AutocompleteProvider,
        sourceName: String,
        onProviderFailure: (String, Exception) -> Unit,
        shouldShow: (InlineCompletionCandidate) -> Boolean,
    ): InlineSelectionResult? {
        val response = requestProviderResponse(provider, request, sourceName, onProviderFailure)
        return selectionFromResponseWithRetry(
            snapshot = snapshot,
            request = request,
            sourceName = sourceName,
            response = response,
            provider = provider,
            onProviderFailure = onProviderFailure,
            shouldShow = shouldShow,
        )
    }

    private suspend fun selectionFromResponseWithRetry(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        sourceName: String,
        response: CompletionResponse?,
        provider: AutocompleteProvider,
        onProviderFailure: (String, Exception) -> Unit,
        shouldShow: (InlineCompletionCandidate) -> Boolean,
    ): InlineSelectionResult? {
        val computation = selectionComputationFromResponse(
            snapshot = snapshot,
            request = request,
            sourceName = sourceName,
            response = response,
            shouldShow = shouldShow,
        )
        computation.selection?.let { return it }

        if (sourceName != FIM_SOURCE) return null
        val retryRequest = computation.retryRequest ?: return null

        val retryResponse = requestProviderResponse(
            provider = provider,
            request = retryRequest,
            sourceName = "${sourceName}_retry",
            onProviderFailure = onProviderFailure,
        )
        return selectionComputationFromResponse(
            snapshot = snapshot,
            request = retryRequest,
            sourceName = "${sourceName}_retry",
            response = retryResponse,
            shouldShow = shouldShow,
        ).selection
    }

    private fun selectionComputationFromResponse(
        snapshot: CompletionContextSnapshot,
        request: AutocompleteRequest,
        sourceName: String,
        response: CompletionResponse?,
        shouldShow: (InlineCompletionCandidate) -> Boolean,
    ): SelectionComputation {
        val preparation = InlineCandidatePreparation.prepareWithDiagnostics(
            rawCandidates = response?.inlineCandidates.orEmpty(),
            request = request,
            snapshot = snapshot,
        )
        if (preparation.candidates.isEmpty()) {
            return SelectionComputation(retryRequest = preparation.retryRequest)
        }

        val filtered = preparation.candidates.filter { candidate ->
            if (candidate.insertionOffset !in 0..snapshot.documentText.length) return@filter false
            val suffix = snapshot.documentText.substring(candidate.insertionOffset)
            if (suffix.startsWith(candidate.text)) return@filter false
            shouldShow(candidate)
        }
        if (filtered.isEmpty()) return SelectionComputation(retryRequest = preparation.retryRequest)
        return SelectionComputation(selection = InlineSelectionResult(filtered, sourceName))
    }

    private suspend fun requestProviderResponse(
        provider: AutocompleteProvider,
        request: AutocompleteRequest,
        sourceName: String,
        onProviderFailure: (String, Exception) -> Unit,
    ): CompletionResponse? = try {
        provider.complete(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onProviderFailure(sourceName, e)
        null
    }

    data class InlineSelectionResult(
        val candidates: List<InlineCompletionCandidate>,
        val sourceName: String,
    )

    private data class SelectionComputation(
        val selection: InlineSelectionResult? = null,
        val retryRequest: AutocompleteRequest? = null,
    )

    data class ProviderInlineResponse(
        val sourceName: String,
        val response: CompletionResponse?,
    )

    private sealed class CompletedResult {
        data class Fim(val result: CompletionResponse?) : CompletedResult()
        data class NextEdit(val result: CompletionResponse?) : CompletedResult()
    }

    private const val FIM_SOURCE = "fim"
}
