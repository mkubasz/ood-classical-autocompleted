package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal enum class AcceptedSuggestionKind(val metricValue: String) {
    INLINE("inline"),
    INLINE_WORD("inline_word"),
    INLINE_LINE("inline_line"),
    NEXT_EDIT("next_edit"),
}

internal class AcceptedSuggestionTracker(
    private val revertWindowMs: Long = DEFAULT_REVERT_WINDOW_MS,
) {
    private val acceptedRanges = mutableListOf<AcceptedRange>()

    @Synchronized
    fun record(
        kind: AcceptedSuggestionKind,
        startOffset: Int,
        insertedText: String,
        acceptedAt: Long = System.currentTimeMillis(),
    ) {
        if (insertedText.isEmpty()) return

        pruneExpired(acceptedAt)
        val safeStart = startOffset.coerceAtLeast(0)
        acceptedRanges += AcceptedRange(
            kind = kind,
            startOffset = safeStart,
            endOffset = safeStart + insertedText.length,
            acceptedAt = acceptedAt,
        )
    }

    @Synchronized
    fun onDocumentChanged(
        changeOffset: Int,
        oldLength: Int,
        newText: String,
        now: Long = System.currentTimeMillis(),
    ): RevertEvent? {
        pruneExpired(now)
        if (acceptedRanges.isEmpty()) return null

        val editStart = changeOffset.coerceAtLeast(0)
        val safeOldLength = oldLength.coerceAtLeast(0)
        val editEnd = editStart + safeOldLength
        val delta = newText.length - safeOldLength
        val updatedRanges = mutableListOf<AcceptedRange>()
        var reverted: RevertEvent? = null

        acceptedRanges.forEach { accepted ->
            when {
                editEnd <= accepted.startOffset -> updatedRanges += accepted.shifted(delta)
                editStart >= accepted.endOffset -> updatedRanges += accepted
                else -> {
                    val overlapStart = maxOf(editStart, accepted.startOffset)
                    val overlapEnd = minOf(editEnd, accepted.endOffset)
                    val overlapLength = (overlapEnd - overlapStart).coerceAtLeast(0)
                    val deletedChars = minOf(
                        overlapLength,
                        (safeOldLength - newText.length).coerceAtLeast(0),
                    )
                    if (deletedChars > 0 && reverted == null) {
                        reverted = RevertEvent(
                            kind = accepted.kind,
                            deletedChars = deletedChars,
                            ageMs = (now - accepted.acceptedAt).coerceAtLeast(0L),
                        )
                    }
                }
            }
        }

        acceptedRanges.clear()
        acceptedRanges.addAll(updatedRanges)
        return reverted
    }

    @Synchronized
    fun clear() {
        acceptedRanges.clear()
    }

    @Synchronized
    internal fun activeRanges(): List<AcceptedRange> = acceptedRanges.toList()

    @Synchronized
    private fun pruneExpired(now: Long) {
        acceptedRanges.removeAll { now - it.acceptedAt > revertWindowMs }
    }

    internal data class RevertEvent(
        val kind: AcceptedSuggestionKind,
        val deletedChars: Int,
        val ageMs: Long,
    )

    internal data class AcceptedRange(
        val kind: AcceptedSuggestionKind,
        val startOffset: Int,
        val endOffset: Int,
        val acceptedAt: Long,
    ) {
        fun shifted(delta: Int): AcceptedRange = copy(
            startOffset = startOffset + delta,
            endOffset = endOffset + delta,
        )
    }

    companion object {
        private const val DEFAULT_REVERT_WINDOW_MS = 30_000L
    }
}
