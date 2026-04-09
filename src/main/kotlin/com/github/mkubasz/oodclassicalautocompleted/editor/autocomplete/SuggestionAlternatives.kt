package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal object SuggestionAlternatives {

    data class Context(
        val documentHash: Int,
        val anchorOffset: Int,
    )

    data class Suggestion(
        val insertionOffset: Int,
        val text: String,
    )

    data class State(
        val context: Context,
        val suggestions: List<Suggestion>,
        val selectedIndex: Int = 0,
    ) {
        init {
            require(suggestions.isNotEmpty()) { "suggestions must not be empty" }
            require(selectedIndex in suggestions.indices) { "selectedIndex out of bounds" }
        }

        val current: Suggestion
            get() = suggestions[selectedIndex]

        val canCycle: Boolean
            get() = suggestions.size > 1

        fun withPrimarySuggestion(suggestion: Suggestion): State =
            copy(
                suggestions = listOf(suggestion) + suggestions.filterNot { it == suggestion },
                selectedIndex = 0,
            )

        fun withAdditionalSuggestion(suggestion: Suggestion): State =
            if (suggestion in suggestions) this else copy(suggestions = suggestions + suggestion)

        fun cycle(step: Int): State {
            if (!canCycle) return this
            val nextIndex = ((selectedIndex + step) % suggestions.size + suggestions.size) % suggestions.size
            return copy(selectedIndex = nextIndex)
        }

        companion object {
            fun single(context: Context, suggestion: Suggestion): State =
                State(
                    context = context,
                    suggestions = listOf(suggestion),
                )
        }
    }
}
