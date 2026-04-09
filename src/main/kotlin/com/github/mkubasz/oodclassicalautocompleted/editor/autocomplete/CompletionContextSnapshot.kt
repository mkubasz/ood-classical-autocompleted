package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import com.intellij.openapi.project.Project

internal data class CompletionContextSnapshot(
    val filePath: String?,
    val language: String?,
    val documentText: String,
    val documentStamp: Long,
    val caretOffset: Int,
    val prefix: String,
    val suffix: String,
    val prefixWindow: String,
    val suffixWindow: String,
    val inlineContext: InlineModelContext? = null,
    val inlineContextSource: InlineContextSource? = null,
    val project: Project? = null,
    val isTerminal: Boolean = false,
)
