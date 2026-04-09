package com.github.mkubasz.oodclassicalautocompleted.completion.domain

data class ResolvedDefinition(
    val name: String,
    val filePath: String?,
    val signature: String,
)
