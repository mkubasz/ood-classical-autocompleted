package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ModelCall
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsGenerationOptions
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsNextEditContextOptions
import kotlinx.coroutines.flow.Flow

internal class AnthropicModelProvider(
    override val id: String,
    apiKey: String,
    baseUrl: String,
    model: String,
    contextBudgetChars: Int,
) : ModelProvider {
    private val runtime = AnthropicProviderRuntime(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        contextBudgetChars = contextBudgetChars,
    )

    override val supportsInline: Boolean = true
    override val supportsNextEdit: Boolean = false

    override suspend fun complete(call: ModelCall): CompletionArtifact? =
        runtime.complete(call.request.toProviderRequest())?.toArtifact(id)

    override suspend fun completeStreaming(call: ModelCall): Flow<String>? =
        runtime.completeStreaming(call.request.toProviderRequest())

    override fun dispose() {
        runtime.dispose()
    }
}

internal class InceptionLabsFimModelProvider(
    override val id: String,
    apiKey: String,
    baseUrl: String,
    model: String,
    generationOptions: InceptionLabsGenerationOptions,
    contextBudgetChars: Int,
) : ModelProvider {
    private val runtime = InceptionLabsFimRuntime(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        generationOptions = generationOptions,
        contextBudgetChars = contextBudgetChars,
    )

    override val supportsInline: Boolean = true
    override val supportsNextEdit: Boolean = false

    override suspend fun complete(call: ModelCall): CompletionArtifact? =
        runtime.complete(call.request.toProviderRequest())?.toArtifact(id)

    override suspend fun completeStreaming(call: ModelCall): Flow<String>? =
        runtime.completeStreaming(call.request.toProviderRequest())

    override fun dispose() {
        runtime.dispose()
    }
}

internal class InceptionLabsNextEditModelProvider(
    override val id: String,
    apiKey: String,
    baseUrl: String,
    model: String,
    generationOptions: InceptionLabsGenerationOptions,
    contextOptions: InceptionLabsNextEditContextOptions,
) : ModelProvider {
    private val runtime = InceptionLabsNextEditRuntime(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        generationOptions = generationOptions,
        contextOptions = contextOptions,
    )

    override val supportsInline: Boolean = true
    override val supportsNextEdit: Boolean = true

    override suspend fun complete(call: ModelCall): CompletionArtifact? =
        runtime.complete(call.request.toProviderRequest())?.toArtifact(id)

    override suspend fun completeStreaming(call: ModelCall): Flow<String>? =
        runtime.completeStreaming(call.request.toProviderRequest())

    override fun dispose() {
        runtime.dispose()
    }
}
