package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteProvider
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteRequest
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.AutocompleteResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

internal object AutocompleteProviderCoordinator {

    suspend fun complete(
        request: AutocompleteRequest,
        fimProvider: AutocompleteProvider?,
        nextEditProvider: AutocompleteProvider?,
    ): AutocompleteResult? = when {
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
    ): AutocompleteResult? = coroutineScope {
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

    private sealed class CompletedResult {
        data class Fim(val result: AutocompleteResult?) : CompletedResult()
        data class NextEdit(val result: AutocompleteResult?) : CompletedResult()
    }
}
