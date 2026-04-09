package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

import kotlinx.serialization.Serializable

@Serializable
enum class RetrievedContextChunkKind {
    SYMBOL,
    IMPORTS,
    CONSTANT,
    LINE_WINDOW,
}

@Serializable
data class RetrievedContextChunk(
    val filePath: String,
    val content: String,
    val score: Double,
    val source: String = "workspace",
    val chunkKind: RetrievedContextChunkKind = RetrievedContextChunkKind.LINE_WINDOW,
    val symbolName: String? = null,
    val language: String? = null,
    val retrievalStage: String = "lexical",
    val selectionBucket: String? = null,
)
