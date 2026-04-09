package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal data class LocalScopeSignals(
    val currentDefinitionName: String? = null,
    val currentParameterNames: List<String> = emptyList(),
    val isFreshBlockBodyContext: Boolean = false,
)

internal object LocalScopeContextHeuristics {

    fun analyze(
        documentText: String,
        caretOffset: Int,
        languageId: String?,
    ): LocalScopeSignals {
        if (!isPythonLike(languageId)) return LocalScopeSignals()

        val safeOffset = caretOffset.coerceIn(0, documentText.length)
        val prefix = documentText.substring(0, safeOffset)
        val suffix = documentText.substring(safeOffset)
        val currentLinePrefix = prefix.substringAfterLast('\n')
        val currentLineSuffix = suffix.substringBefore('\n')
        val linesBeforeCaret = prefix.lineSequence().toList()
        val currentIndent = indentationWidth(currentLinePrefix)

        val currentHeader = currentLinePrefix
            .trimStart()
            .takeIf(::isPythonDefinitionHeader)
        if (currentHeader != null && currentLineSuffix.isBlank()) {
            return LocalScopeSignals(
                currentDefinitionName = extractDefinitionName(currentHeader),
                currentParameterNames = extractParameterNames(currentHeader),
                isFreshBlockBodyContext = currentHeader.trimEnd().endsWith(":"),
            )
        }

        val previousNonBlankLine = linesBeforeCaret
            .dropLast(1)
            .lastOrNull { it.isNotBlank() }
        val isFreshBlockBodyContext = currentLinePrefix.isBlank() &&
            previousNonBlankLine != null &&
            isPythonBlockHeader(previousNonBlankLine.trimStart()) &&
            indentationWidth(previousNonBlankLine) < currentIndent

        val nearestDefinitionHeader = findNearestDefinitionHeader(
            linesBeforeCaret = linesBeforeCaret.dropLast(1),
            currentIndent = currentIndent,
        )

        return LocalScopeSignals(
            currentDefinitionName = nearestDefinitionHeader?.let(::extractDefinitionName),
            currentParameterNames = nearestDefinitionHeader?.let(::extractParameterNames).orEmpty(),
            isFreshBlockBodyContext = isFreshBlockBodyContext,
        )
    }

    private fun findNearestDefinitionHeader(
        linesBeforeCaret: List<String>,
        currentIndent: Int,
    ): String? = linesBeforeCaret.asReversed()
        .firstOrNull { line ->
            val trimmed = line.trimStart()
            isPythonDefinitionHeader(trimmed) && indentationWidth(line) < currentIndent + 1
        }
        ?.trimStart()

    private fun extractDefinitionName(header: String): String? = DEFINITION_NAME_PATTERN
        .find(header)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)

    private fun extractParameterNames(header: String): List<String> {
        val paramsBlock = header.substringAfter('(', "")
            .substringBeforeLast(')', "")
            .ifBlank { return emptyList() }

        return paramsBlock.split(',')
            .map { param ->
                param.trim()
                    .removePrefix("*")
                    .removePrefix("*")
                    .substringBefore(':')
                    .substringBefore('=')
                    .trim()
            }
            .filter { it.isNotBlank() && it != "self" && it != "cls" }
            .distinct()
    }

    private fun indentationWidth(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    private fun isPythonLike(languageId: String?): Boolean {
        val normalized = languageId.orEmpty().trim().lowercase()
        return normalized.contains("python") || normalized == "py"
    }

    private fun isPythonDefinitionHeader(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("def ") || trimmed.startsWith("async def ") || trimmed.startsWith("class ")
    }

    private fun isPythonBlockHeader(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.endsWith(":") && BLOCK_HEADER_PREFIXES.any(trimmed::startsWith)
    }

    private val DEFINITION_NAME_PATTERN = Regex("""(?:async\s+def|def|class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val BLOCK_HEADER_PREFIXES = listOf(
        "async def ",
        "def ",
        "class ",
        "if ",
        "elif ",
        "else:",
        "for ",
        "while ",
        "with ",
        "try:",
        "except",
        "finally:",
        "match ",
        "case ",
    )
}
