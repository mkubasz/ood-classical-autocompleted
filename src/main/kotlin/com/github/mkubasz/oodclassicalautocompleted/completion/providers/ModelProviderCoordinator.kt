package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

internal object ModelProviderCoordinator {
    suspend fun completeInline(
        call: ModelCall,
        primary: ModelProvider?,
        fallback: ModelProvider?,
        onFailure: (String, Exception) -> Unit = { _, _ -> },
    ): CompletionArtifact? = when {
        primary != null && fallback != null -> parallelInline(call, primary, fallback, onFailure)
        primary != null -> request(primary, call, onFailure)
        fallback != null -> request(fallback, call, onFailure)
        else -> null
    }?.takeIf { it.inlineCandidates.isNotEmpty() }

    suspend fun completeNextEdit(
        call: ModelCall,
        provider: ModelProvider?,
        onFailure: (String, Exception) -> Unit = { _, _ -> },
    ): CompletionArtifact? = provider?.let { request(it, call, onFailure) }

    private suspend fun parallelInline(
        call: ModelCall,
        primary: ModelProvider,
        fallback: ModelProvider,
        onFailure: (String, Exception) -> Unit,
    ): CompletionArtifact? = coroutineScope {
        val primaryDeferred = async { request(primary, call, onFailure) }
        val fallbackDeferred = async { request(fallback, call, onFailure) }

        repeat(2) {
            when (val completed = select<CompletedArtifact> {
                if (primaryDeferred.isActive) {
                    primaryDeferred.onAwait { CompletedArtifact.Primary(it) }
                }
                if (fallbackDeferred.isActive) {
                    fallbackDeferred.onAwait { CompletedArtifact.Fallback(it) }
                }
            }) {
                is CompletedArtifact.Primary -> {
                    if (!completed.value?.inlineCandidates.isNullOrEmpty()) {
                        fallbackDeferred.cancel()
                        return@coroutineScope completed.value
                    }
                }

                is CompletedArtifact.Fallback -> {
                    if (!completed.value?.inlineCandidates.isNullOrEmpty()) {
                        primaryDeferred.cancel()
                        return@coroutineScope completed.value
                    }
                }
            }
        }
        null
    }

    private suspend fun request(
        provider: ModelProvider,
        call: ModelCall,
        onFailure: (String, Exception) -> Unit,
    ): CompletionArtifact? = try {
        provider.complete(call)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(provider.id, e)
        null
    }

    private sealed interface CompletedArtifact {
        data class Primary(val value: CompletionArtifact?) : CompletedArtifact
        data class Fallback(val value: CompletionArtifact?) : CompletedArtifact
    }
}
