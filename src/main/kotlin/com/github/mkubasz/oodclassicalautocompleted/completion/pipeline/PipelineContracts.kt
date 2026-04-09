package com.github.mkubasz.oodclassicalautocompleted.completion.pipeline

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ContextBlock
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.EditorSnapshot
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineMode
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PreprocessingFacts
import com.github.mkubasz.oodclassicalautocompleted.completion.languages.LanguageSupport
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CodeSnippet
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunk

internal data class ContextContribution(
    val blocks: List<ContextBlock> = emptyList(),
    val inlineContext: InlineModelContext? = null,
    val inlineContextSource: String? = null,
    val retrievedChunks: List<RetrievedContextChunk> = emptyList(),
    val recentlyViewedSnippets: List<CodeSnippet> = emptyList(),
    val editDiffHistory: List<String> = emptyList(),
    val gitDiff: String? = null,
) {
    fun merge(other: ContextContribution): ContextContribution = ContextContribution(
        blocks = blocks + other.blocks,
        inlineContext = inlineContext ?: other.inlineContext,
        inlineContextSource = inlineContextSource ?: other.inlineContextSource,
        retrievedChunks = retrievedChunks + other.retrievedChunks,
        recentlyViewedSnippets = recentlyViewedSnippets + other.recentlyViewedSnippets,
        editDiffHistory = editDiffHistory + other.editDiffHistory,
        gitDiff = gitDiff ?: other.gitDiff,
    )
}

internal interface PreProcessor {
    val name: String

    suspend fun process(snapshot: EditorSnapshot, mode: PipelineMode): PreprocessingFacts
}

internal interface ContextContributor {
    val name: String

    suspend fun contribute(
        snapshot: EditorSnapshot,
        mode: PipelineMode,
        languageSupport: LanguageSupport,
        existing: ContextContribution,
    ): ContextContribution
}

internal interface PostProcessor {
    val name: String

    suspend fun process(
        request: PipelineRequest,
        artifact: CompletionArtifact,
    ): CompletionArtifact
}

internal interface BudgetEstimator {
    fun estimate(block: ContextBlock): Int = block.costChars
}
