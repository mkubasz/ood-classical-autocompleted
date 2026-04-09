package com.github.mkubasz.oodclassicalautocompleted.completion.providers

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ProviderResponse

internal fun PipelineRequest.toProviderRequest(): ProviderRequest = ProviderRequest(
    prefix = snapshot.prefix,
    suffix = snapshot.suffix,
    filePath = snapshot.filePath,
    language = snapshot.languageId,
    cursorOffset = snapshot.caretOffset,
    inlineContext = inlineContext,
    retrievedChunks = retrievedChunks.takeIf { it.isNotEmpty() },
    recentlyViewedSnippets = recentlyViewedSnippets.takeIf { it.isNotEmpty() },
    editDiffHistory = editDiffHistory.takeIf { it.isNotEmpty() },
    gitDiff = gitDiff,
    packedContextSummary = packedContext.summary.takeIf { it.isNotBlank() },
)

internal fun ProviderResponse.toArtifact(sourceName: String): CompletionArtifact = CompletionArtifact(
    inlineCandidates = inlineCandidates,
    nextEditCandidates = nextEditCandidates,
    sourceName = sourceName,
)
