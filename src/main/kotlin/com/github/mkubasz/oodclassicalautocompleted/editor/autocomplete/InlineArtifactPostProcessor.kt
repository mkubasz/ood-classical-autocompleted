package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.CompletionArtifact
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.PipelineRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.pipeline.PostProcessor
import com.github.mkubasz.oodclassicalautocompleted.completion.providers.toProviderRequest
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineCompletionCandidate

internal class InlineArtifactPostProcessor(
    private val optionsProvider: () -> InlineCandidatePreparation.Options,
    private val shouldShow: (CompletionContextSnapshot, InlineCompletionCandidate) -> Boolean,
    private val maxSuggestionChars: Int = DEFAULT_MAX_SUGGESTION_CHARS,
) : PostProcessor {
    override val name: String = "inline_preparation"

    override suspend fun process(
        request: PipelineRequest,
        artifact: CompletionArtifact,
    ): CompletionArtifact {
        if (artifact.inlineCandidates.isEmpty()) return artifact
        return artifact.copy(
            inlineCandidates = prepare(request, artifact.inlineCandidates),
        )
    }

    fun prepare(
        request: PipelineRequest,
        rawCandidates: List<InlineCompletionCandidate>,
    ): List<InlineCompletionCandidate> {
        if (rawCandidates.isEmpty()) return emptyList()
        val snapshot = request.toCompletionContextSnapshot()
        return InlineCandidatePreparation.prepare(
            rawCandidates = rawCandidates,
            request = request.toProviderRequest(),
            snapshot = snapshot,
            maxSuggestionChars = maxSuggestionChars,
            options = optionsProvider(),
        ).filter { candidate -> shouldShow(snapshot, candidate) }
    }

    fun filterPrepared(
        request: PipelineRequest,
        candidates: List<InlineCompletionCandidate>,
    ): List<InlineCompletionCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val snapshot = request.toCompletionContextSnapshot()
        return candidates.filter { candidate -> shouldShow(snapshot, candidate) }
    }

    private fun PipelineRequest.toCompletionContextSnapshot(): CompletionContextSnapshot = CompletionContextSnapshot(
        filePath = snapshot.filePath,
        language = snapshot.languageId,
        documentText = snapshot.documentText,
        documentStamp = snapshot.documentVersion,
        caretOffset = snapshot.caretOffset,
        prefix = snapshot.prefix,
        suffix = snapshot.suffix,
        prefixWindow = snapshot.prefixWindow,
        suffixWindow = snapshot.suffixWindow,
        inlineContext = inlineContext,
        inlineContextSource = null,
        project = snapshot.project,
        isTerminal = snapshot.isTerminal,
    )

    companion object {
        private const val DEFAULT_MAX_SUGGESTION_CHARS = 400
    }
}
