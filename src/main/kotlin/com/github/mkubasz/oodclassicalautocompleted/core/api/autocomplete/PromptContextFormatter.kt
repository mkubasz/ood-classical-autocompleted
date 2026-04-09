package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object PromptContextFormatter {

    fun formatForCodePrefix(request: AutocompleteRequest): String = buildString {
        val inlineContext = request.inlineContext
            ?.let { InlineModelContextFormatter.formatForCodePrefix(it, request.language) }
            .orEmpty()
        val retrievedContext = RetrievedContextFormatter.formatForCodePrefix(
            chunks = request.retrievedChunks.orEmpty(),
            language = request.language,
        )

        if (inlineContext.isNotBlank()) append(inlineContext)
        if (inlineContext.isNotBlank() && retrievedContext.isNotBlank()) appendLine()
        if (retrievedContext.isNotBlank()) append(retrievedContext)
    }

    fun formatForInstructionPrompt(request: AutocompleteRequest): String = buildString {
        val inlineContext = request.inlineContext
            ?.let { InlineModelContextFormatter.formatForInstructionPrompt(it) }
            .orEmpty()
        val retrievedContext = RetrievedContextFormatter.formatForInstructionPrompt(request.retrievedChunks.orEmpty())

        if (inlineContext.isNotBlank()) append(inlineContext)
        if (inlineContext.isNotBlank() && retrievedContext.isNotBlank()) appendLine()
        if (retrievedContext.isNotBlank()) append(retrievedContext)
    }
}

internal object RetrievedContextFormatter {

    fun formatForCodePrefix(
        chunks: List<RetrievedContextChunk>,
        language: String?,
    ): String {
        if (chunks.isEmpty()) return ""

        val commentPrefix = PromptCommentPrefix.forLanguage(language)
        return buildString {
            appendLine("$commentPrefix Workspace retrieval context:")
            chunks.forEach { chunk ->
                appendLine("$commentPrefix retrieved_chunk: ${chunk.filePath.substringAfterLast('/')} score=${"%.2f".format(chunk.score)}")
                chunk.content.lineSequence().forEach { line ->
                    appendLine(if (line.isBlank()) commentPrefix else "$commentPrefix $line")
                }
            }
        }
    }

    fun formatForInstructionPrompt(chunks: List<RetrievedContextChunk>): String {
        if (chunks.isEmpty()) return ""

        return buildString {
            appendLine("Workspace retrieval context:")
            chunks.forEach { chunk ->
                appendLine("retrieved_chunk: ${chunk.filePath} score=${"%.2f".format(chunk.score)}")
                appendLine(chunk.content)
            }
        }
    }
}

internal object PromptCommentPrefix {

    fun forLanguage(language: String?): String {
        val normalized = language.orEmpty().trim().lowercase()
        return when {
            normalized.isBlank() -> "//"
            normalized in hashCommentLanguages ||
                normalized.contains("python") ||
                normalized.contains("shell") ||
                normalized.contains("bash") ||
                normalized.contains("fish") -> "#"
            normalized in doubleDashCommentLanguages -> "--"
            normalized in slashSlashCommentLanguages ||
                normalized == "golang" ||
                normalized.contains("rust") ||
                normalized.contains("java") ||
                normalized.contains("kotlin") ||
                normalized.contains("json") ||
                normalized.contains("php") -> "//"
            else -> "//"
        }
    }

    private val hashCommentLanguages = setOf(
        "py",
        "python",
        "rb",
        "ruby",
        "sh",
        "shell",
        "shell script",
        "bash",
        "fish",
        "fish shell",
        "yaml",
        "yml",
        "toml",
        "dockerfile",
    )

    private val doubleDashCommentLanguages = setOf(
        "sql",
        "lua",
        "haskell",
    )

    private val slashSlashCommentLanguages = setOf(
        "go",
        "rust",
        "java",
        "kotlin",
        "kt",
        "json",
        "json5",
        "php",
    )
}
