package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

data class ResolvedDefinition(
    val name: String,
    val filePath: String?,
    val signature: String,
)
