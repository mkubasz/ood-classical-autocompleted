package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.ResolvedDefinition

/**
 * Lightweight regex-based context extraction for languages where PSI resolution yields nothing.
 * Extracts import targets, function/class signatures, and type names from raw source text.
 */
internal object HeuristicContextFallback {

    fun extractDefinitions(
        documentText: String,
        caretOffset: Int,
        maxDefinitions: Int = MAX_DEFINITIONS,
        maxChars: Int = MAX_CHARS,
    ): List<ResolvedDefinition> {
        val definitions = mutableListOf<ResolvedDefinition>()
        var totalChars = 0

        val nearbyIdentifiers = extractNearbyIdentifiers(documentText, caretOffset)

        for (identifier in nearbyIdentifiers) {
            if (definitions.size >= maxDefinitions || totalChars >= maxChars) break

            val signature = findDefinitionSignature(documentText, identifier)
            if (signature != null && signature.length > identifier.length) {
                definitions.add(ResolvedDefinition(name = identifier, filePath = null, signature = signature))
                totalChars += signature.length
            }
        }

        val importDefs = extractImportContext(documentText)
        for (def in importDefs) {
            if (definitions.size >= maxDefinitions || totalChars >= maxChars) break
            if (definitions.none { it.name == def.name }) {
                definitions.add(def)
                totalChars += def.signature.length
            }
        }

        return definitions
    }

    private fun extractNearbyIdentifiers(text: String, caretOffset: Int): List<String> {
        val scanStart = (caretOffset - SCAN_RADIUS).coerceAtLeast(0)
        val scanEnd = (caretOffset + SCAN_RADIUS).coerceAtMost(text.length)
        val nearbyText = text.substring(scanStart, scanEnd)

        return IDENTIFIER_PATTERN.findAll(nearbyText)
            .map { it.value }
            .filter { it.length >= 2 && it !in COMMON_KEYWORDS }
            .distinct()
            .take(MAX_IDENTIFIERS)
            .toList()
    }

    private fun findDefinitionSignature(text: String, identifier: String): String? {
        for (pattern in DEFINITION_PATTERNS) {
            val regex = Regex(pattern.replace("{NAME}", Regex.escape(identifier)), RegexOption.MULTILINE)
            val match = regex.find(text) ?: continue
            val startLine = text.lastIndexOf('\n', (match.range.first - 1).coerceAtLeast(0))
                .let { if (it < 0) 0 else it + 1 }
            val lines = text.substring(startLine).lineSequence()
                .take(MAX_SIGNATURE_LINES)
                .toList()
            return lines.joinToString("\n").take(MAX_SIGNATURE_CHARS).trimEnd()
        }
        return null
    }

    private fun extractImportContext(text: String): List<ResolvedDefinition> {
        val imports = mutableListOf<ResolvedDefinition>()
        for (pattern in IMPORT_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                val module = match.groupValues.getOrNull(1)?.trim() ?: return@forEach
                if (module.isNotBlank() && imports.size < MAX_IMPORT_DEFS) {
                    imports.add(ResolvedDefinition(
                        name = module.substringAfterLast('.').substringAfterLast('/'),
                        filePath = null,
                        signature = match.value.trim(),
                    ))
                }
            }
        }
        return imports
    }

    private val IDENTIFIER_PATTERN = Regex("""[A-Z][A-Za-z0-9_]{2,}|[a-z][A-Za-z0-9_]{3,}""")

    private val DEFINITION_PATTERNS = listOf(
        """(?:def|func|function|fn)\s+{NAME}\s*\(""",
        """(?:class|struct|interface|enum|type)\s+{NAME}\b""",
        """(?:val|var|let|const|final)\s+{NAME}\s*[:=]""",
        """{NAME}\s*:=\s*func""",
        """{NAME}\s*=\s*(?:function|class|\()""",
    )

    private val IMPORT_PATTERNS = listOf(
        Regex("""^\s*import\s+(.+)$""", RegexOption.MULTILINE),
        Regex("""^\s*from\s+(\S+)\s+import""", RegexOption.MULTILINE),
        Regex("""^\s*(?:const|let|var)\s+.+\s*=\s*require\(['"](.+?)['"]\)""", RegexOption.MULTILINE),
        Regex("""^\s*import\s+.+\s+from\s+['"](.+?)['"]""", RegexOption.MULTILINE),
        Regex("""^\s*use\s+(.+?);""", RegexOption.MULTILINE),
    )

    private val COMMON_KEYWORDS = setOf(
        "true", "false", "null", "None", "self", "this", "super", "return", "break", "continue",
        "else", "elif", "catch", "finally", "throw", "throws", "void", "string", "boolean",
        "number", "undefined", "print", "println", "console", "import", "from", "class", "function",
        "const", "type", "interface", "struct", "enum", "package", "module",
    )

    private const val SCAN_RADIUS = 500
    private const val MAX_IDENTIFIERS = 10
    private const val MAX_DEFINITIONS = 4
    private const val MAX_IMPORT_DEFS = 3
    private const val MAX_CHARS = 1200
    private const val MAX_SIGNATURE_LINES = 3
    private const val MAX_SIGNATURE_CHARS = 250
}
