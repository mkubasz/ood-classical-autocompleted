package com.github.mkubasz.oodclassicalautocompleted.completion.domain

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CodeSnippet
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.NextEditCompletionCandidate
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunk
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

internal enum class PipelineMode {
    INLINE,
    NEXT_EDIT,
}

internal enum class ContextKind {
    CURSOR_WINDOW,
    EDITABLE_REGION,
    ACTIVE_SYMBOL,
    VISIBLE_SYMBOLS,
    DEPENDENCY_SYMBOLS,
    RECENT_EDITS,
    GIT_DIFF,
    WORKSPACE,
}

internal enum class ContextScope {
    LOCAL,
    SAME_FILE,
    SAME_DIRECTORY,
    SAME_MODULE,
    PROJECT,
    LIBRARY,
    RECENT,
}

internal data class ContextBlock(
    val kind: ContextKind,
    val scope: ContextScope,
    val source: String,
    val priority: Int,
    val freshness: Int = 0,
    val language: String? = null,
    val title: String? = null,
    val content: String,
) {
    val costChars: Int
        get() = content.length
}

internal data class EditorSnapshot(
    val project: Project,
    val document: Document,
    val filePath: String?,
    val languageId: String?,
    val documentText: String,
    val documentVersion: Long,
    val caretOffset: Int,
    val prefix: String,
    val suffix: String,
    val prefixWindow: String,
    val suffixWindow: String,
    val requestId: Long,
    val isTerminal: Boolean,
)

internal data class PreprocessingFacts(
    val shouldContinue: Boolean = true,
    val skipReason: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    fun merge(other: PreprocessingFacts): PreprocessingFacts = PreprocessingFacts(
        shouldContinue = shouldContinue && other.shouldContinue,
        skipReason = skipReason ?: other.skipReason,
        attributes = attributes + other.attributes,
    )
}

internal data class PackedContext(
    val blocks: List<ContextBlock>,
    val summary: String,
    val totalChars: Int,
    val budgetChars: Int,
)

internal data class PipelineRequest(
    val mode: PipelineMode,
    val snapshot: EditorSnapshot,
    val preprocessingFacts: PreprocessingFacts,
    val packedContext: PackedContext,
    val inlineContext: InlineModelContext? = null,
    val retrievedChunks: List<RetrievedContextChunk> = emptyList(),
    val recentlyViewedSnippets: List<CodeSnippet> = emptyList(),
    val editDiffHistory: List<String> = emptyList(),
    val gitDiff: String? = null,
)

internal data class CompletionArtifact(
    val inlineCandidates: List<InlineCompletionCandidate> = emptyList(),
    val nextEditCandidates: List<NextEditCompletionCandidate> = emptyList(),
    val sourceName: String? = null,
)

internal data class ModelCall(
    val providerId: String,
    val request: PipelineRequest,
)

internal data class StageTiming(
    val stage: String,
    val elapsedMs: Long,
)

internal data class ContributorTiming(
    val name: String,
    val elapsedMs: Long,
    val emittedBlocks: Int,
)

internal data class PipelineTrace(
    val stageTimings: List<StageTiming> = emptyList(),
    val contributorTimings: List<ContributorTiming> = emptyList(),
)
