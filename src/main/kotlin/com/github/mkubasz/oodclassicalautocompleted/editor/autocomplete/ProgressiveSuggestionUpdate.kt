package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal object ProgressiveSuggestionUpdate {

    data class Edit(
        val offset: Int,
        val oldLength: Int,
        val newText: String,
    )

    data class ActiveSuggestion(
        val anchorOffset: Int,
        val insertionOffset: Int,
        val text: String,
    )

    sealed interface Outcome {
        data class Updated(val suggestion: ActiveSuggestion) : Outcome
        data class OffsetShifted(val suggestion: ActiveSuggestion) : Outcome
        data object Consumed : Outcome
        data object NoMatch : Outcome
    }

    fun apply(
        activeSuggestion: ActiveSuggestion,
        edit: Edit,
    ): Outcome {
        if (activeSuggestion.anchorOffset != activeSuggestion.insertionOffset) {
            return applyOffCaret(activeSuggestion, edit)
        }

        if (edit.oldLength != 0) return Outcome.NoMatch
        if (edit.newText.isEmpty()) return Outcome.NoMatch
        if (edit.offset != activeSuggestion.anchorOffset) return Outcome.NoMatch
        if (!activeSuggestion.text.startsWith(edit.newText)) return Outcome.NoMatch

        val remainingText = activeSuggestion.text.drop(edit.newText.length)
        if (remainingText.isEmpty()) return Outcome.Consumed

        val updatedOffset = activeSuggestion.anchorOffset + edit.newText.length
        return Outcome.Updated(
            activeSuggestion.copy(
                anchorOffset = updatedOffset,
                insertionOffset = updatedOffset,
                text = remainingText,
            )
        )
    }

    private fun applyOffCaret(
        activeSuggestion: ActiveSuggestion,
        edit: Edit,
    ): Outcome {
        val editEnd = edit.offset + edit.oldLength
        val suggestionEnd = activeSuggestion.insertionOffset + activeSuggestion.text.length

        if (edit.offset < suggestionEnd && editEnd > activeSuggestion.insertionOffset) {
            return Outcome.NoMatch
        }

        if (editEnd <= activeSuggestion.insertionOffset) {
            val delta = edit.newText.length - edit.oldLength
            return Outcome.OffsetShifted(
                activeSuggestion.copy(
                    insertionOffset = activeSuggestion.insertionOffset + delta,
                )
            )
        }

        return Outcome.OffsetShifted(activeSuggestion)
    }
}
