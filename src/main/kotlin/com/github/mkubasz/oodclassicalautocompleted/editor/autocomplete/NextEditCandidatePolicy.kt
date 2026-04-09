package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.NextEditCompletionCandidate

internal object NextEditCandidatePolicy {

    fun previewCandidateOrNull(
        candidate: NextEditCompletionCandidate,
        snapshot: CompletionContextSnapshot,
        maxPreviewLines: Int,
        maxPreviewChars: Int,
    ): NextEditCompletionCandidate? {
        if (shouldSuppressForCaretContext(snapshot)) return null
        if (candidate.startOffset !in 0..snapshot.documentText.length) return null
        if (candidate.endOffset !in candidate.startOffset..snapshot.documentText.length) return null
        if (candidate.replacementText.length > maxPreviewChars) return null
        if (candidate.replacementText.lines().size > maxPreviewLines) return null

        val insertionAtCaret = candidate.startOffset == snapshot.caretOffset &&
            candidate.endOffset == snapshot.caretOffset
        if (insertionAtCaret && '\n' !in candidate.replacementText) return null
        if (looksLikeDuplicateStructuredBlock(candidate, snapshot)) return null

        return candidate
    }

    private fun shouldSuppressForCaretContext(snapshot: CompletionContextSnapshot): Boolean {
        val currentLinePrefix = snapshot.prefix.substringAfterLast('\n').trimStart()
        val context = snapshot.inlineContext
        return context?.isDecoratorLikeContext == true ||
            currentLinePrefix.startsWith("@") ||
            context?.isDefinitionHeaderLikeContext == true ||
            context?.isInParameterListLikeContext == true
    }

    private fun looksLikeDuplicateStructuredBlock(
        candidate: NextEditCompletionCandidate,
        snapshot: CompletionContextSnapshot,
    ): Boolean {
        val normalizedReplacement = normalizeBlock(candidate.replacementText) ?: return false
        val replacementLines = normalizedReplacement.lines()
        if (replacementLines.size < MIN_DUPLICATE_BLOCK_LINES || normalizedReplacement.length < MIN_DUPLICATE_BLOCK_CHARS) {
            return false
        }

        val nearbyBefore = snapshot.documentText.substring(
            startIndex = (candidate.startOffset - NEARBY_DUPLICATE_WINDOW_CHARS).coerceAtLeast(0),
            endIndex = candidate.startOffset,
        )
        val nearbyAfter = snapshot.documentText.substring(
            startIndex = candidate.endOffset,
            endIndex = (candidate.endOffset + NEARBY_DUPLICATE_WINDOW_CHARS).coerceAtMost(snapshot.documentText.length),
        )

        if (containsNormalizedBlock(nearbyBefore, normalizedReplacement)) return true
        if (containsNormalizedBlock(nearbyAfter, normalizedReplacement)) return true

        val replacementHeaders = StructuredPsiHeaderExtractor.extract(
            project = snapshot.project,
            text = normalizedReplacement,
            filePath = snapshot.filePath,
        )
        if (replacementHeaders.size < MIN_DUPLICATE_HEADERS || replacementLines.size < MIN_MULTI_DEFINITION_BLOCK_LINES) {
            return false
        }

        val nearbyHeaders = buildSet {
            addAll(
                StructuredPsiHeaderExtractor.extract(
                    project = snapshot.project,
                    text = nearbyBefore,
                    filePath = snapshot.filePath,
                )
            )
            addAll(
                StructuredPsiHeaderExtractor.extract(
                    project = snapshot.project,
                    text = nearbyAfter,
                    filePath = snapshot.filePath,
                )
            )
        }
        return replacementHeaders.count { it in nearbyHeaders } >= MIN_DUPLICATE_HEADERS
    }

    private fun containsNormalizedBlock(haystack: String, needle: String): Boolean {
        val normalizedHaystack = normalizeBlock(haystack) ?: return false
        return normalizedHaystack.contains(needle)
    }

    private fun normalizeBlock(text: String): String? {
        val normalizedLines = text.replace("\r", "")
            .lineSequence()
            .map(String::trimEnd)
            .dropWhile(String::isBlank)
            .toList()
            .dropLastWhile(String::isBlank)

        if (normalizedLines.isEmpty()) return null
        return normalizedLines.joinToString("\n")
    }

    private const val NEARBY_DUPLICATE_WINDOW_CHARS = 4_000
    private const val MIN_DUPLICATE_BLOCK_LINES = 3
    private const val MIN_DUPLICATE_BLOCK_CHARS = 40
    private const val MIN_DUPLICATE_HEADERS = 2
    private const val MIN_MULTI_DEFINITION_BLOCK_LINES = 5
}
