package com.github.mkubasz.oodclassicalautocompleted.completion.domain

data class ProviderRequest(
    val prefix: String,
    val suffix: String,
    val filePath: String?,
    val language: String?,
    val cursorOffset: Int? = null,
    val inlineContext: InlineModelContext? = null,
    val retrievedChunks: List<RetrievedContextChunk>? = null,
    val recentlyViewedSnippets: List<CodeSnippet>? = null,
    val editDiffHistory: List<String>? = null,
    val gitDiff: String? = null,
    val packedContextSummary: String? = null,
)

data class InlineModelContext(
    val lexicalContext: InlineLexicalContext = InlineLexicalContext.UNKNOWN,
    val enclosingNames: List<String> = emptyList(),
    val enclosingKinds: List<String> = emptyList(),
    val currentDefinitionName: String? = null,
    val currentParameterNames: List<String> = emptyList(),
    val isFreshBlockBodyContext: Boolean = false,
    val isDecoratorLikeContext: Boolean = false,
    val headerValidationRetry: Boolean = false,
    val isClassBaseListLikeContext: Boolean = false,
    val isAfterMemberAccess: Boolean = false,
    val receiverExpression: String? = null,
    val receiverMemberNames: List<String> = emptyList(),
    val isInParameterListLikeContext: Boolean = false,
    val isDefinitionHeaderLikeContext: Boolean = false,
    val classBaseReferencePrefix: String? = null,
    val matchingTypeNames: List<String> = emptyList(),
    val headerValidationError: String? = null,
    val expectedHeaderContinuation: String? = null,
    val resolvedReferenceName: String? = null,
    val resolvedFilePath: String? = null,
    val resolvedSnippet: String? = null,
    val resolvedDefinitions: List<ResolvedDefinition> = emptyList(),
)

enum class InlineLexicalContext {
    CODE,
    COMMENT,
    STRING,
    UNKNOWN,
}

data class ProviderResponse(
    val inlineCandidates: List<InlineCompletionCandidate> = emptyList(),
    val nextEditCandidates: List<NextEditCompletionCandidate> = emptyList(),
)

data class InlineCompletionCandidate(
    val text: String,
    val insertionOffset: Int,
    val isExactInsertion: Boolean = false,
    val confidenceScore: Double? = null,
)

data class NextEditCompletionCandidate(
    val startOffset: Int,
    val endOffset: Int,
    val replacementText: String,
)
