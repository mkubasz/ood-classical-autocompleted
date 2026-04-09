package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import kotlinx.coroutines.flow.Flow

internal interface ModelProvider {
    val id: String
    val supportsInline: Boolean
    val supportsNextEdit: Boolean

    suspend fun complete(call: ModelCall): CompletionArtifact?
    suspend fun completeStreaming(call: ModelCall): Flow<String>? = null
    fun dispose()
}

internal data class InlineProviderSelection(
    val primary: ModelProvider?,
    val fallback: ModelProvider?,
    val providerKey: String,
)
