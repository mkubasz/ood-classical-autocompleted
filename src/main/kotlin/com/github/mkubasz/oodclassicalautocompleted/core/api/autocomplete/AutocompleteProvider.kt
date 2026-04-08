package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

interface AutocompleteProvider {
    suspend fun complete(request: AutocompleteRequest): AutocompleteResult?
    fun cancel()
    fun dispose()
}

data class AutocompleteRequest(
    val prefix: String,
    val suffix: String,
    val filePath: String?,
    val language: String?,
    val cursorOffset: Int? = null,
    val recentlyViewedSnippets: List<CodeSnippet>? = null,
    val editDiffHistory: List<String>? = null,
)

data class CodeSnippet(
    val filePath: String,
    val content: String,
)

data class AutocompleteResult(
    val text: String,
    val insertionOffset: Int? = null,
    val isExactInsertion: Boolean = false,
)
