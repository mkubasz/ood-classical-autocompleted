package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

internal enum class AutocompleteMetricType(val eventName: String) {
    INLINE_SKIP("inline.skip"),
    INLINE_CONTEXT("inline.context"),
    INLINE_CACHE_HIT("inline.cache.hit"),
    INLINE_CACHE_PROXIMITY_HIT("inline.cache.proximity_hit"),
    INLINE_CACHE_MISS("inline.cache.miss"),
    INLINE_SOURCE("inline.source"),
    INLINE_STREAM_STARTED("inline.stream.started"),
    INLINE_STREAM_COMPLETED("inline.stream.completed"),
    INLINE_STREAM_CANCELLED("inline.stream.cancelled"),
    INLINE_SHOWN("inline.shown"),
    INLINE_ACCEPTED("inline.accepted"),
    INLINE_ACCEPTED_INLINE_ONLY("inline.accepted.inline_only"),
    INLINE_ACCEPT_WORD("inline.accept_word"),
    INLINE_ACCEPT_LINE("inline.accept_line"),
    INLINE_CYCLE("inline.cycle"),
    INLINE_REJECTED("inline.rejected"),
    INLINE_CORRECTNESS("inline.correctness"),
    INLINE_LATENCY("inline.latency"),
    NEXT_EDIT_READY("next_edit.ready"),
    NEXT_EDIT_PREVIEWED("next_edit.previewed"),
    NEXT_EDIT_ACCEPTED("next_edit.accepted"),
    NEXT_EDIT_REJECTED("next_edit.rejected"),
    RETRIEVAL_HIT("retrieval.hit"),
    RETRIEVAL_MISS("retrieval.miss"),
    ACCEPTED_TEXT_REVERTED("accepted_text.reverted"),
}

internal data class AutocompleteMetricEvent(
    val type: AutocompleteMetricType,
    val fields: List<Pair<String, Any?>> = emptyList(),
) {
    fun formatForLog(): String {
        val body = fields
            .filter { (_, value) -> value != null }
            .joinToString(", ") { (key, value) -> "$key=$value" }
        return if (body.isBlank()) {
            type.eventName
        } else {
            "${type.eventName} $body"
        }
    }
}

internal class AutocompleteMetrics(
    private val isEnabled: () -> Boolean,
    private val sink: (AutocompleteMetricEvent) -> Unit,
) {
    fun record(type: AutocompleteMetricType, vararg fields: Pair<String, Any?>) {
        if (!isEnabled()) return
        sink(AutocompleteMetricEvent(type, fields.toList()))
    }
}
