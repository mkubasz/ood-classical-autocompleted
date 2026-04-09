package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteMetricsTest {

    @Test
    fun formatsEventWithoutFields() {
        val event = AutocompleteMetricEvent(AutocompleteMetricType.INLINE_SHOWN)

        assertEquals("inline.shown", event.formatForLog())
    }

    @Test
    fun formatsEventWithOrderedFieldsAndSkipsNullValues() {
        val event = AutocompleteMetricEvent(
            type = AutocompleteMetricType.INLINE_LATENCY,
            fields = listOf(
                "total_ms" to 18,
                "source" to "cache",
                "ignored" to null,
            ),
        )

        assertEquals("inline.latency total_ms=18, source=cache", event.formatForLog())
    }

    @Test
    fun suppressesEventsWhenDisabled() {
        val emitted = mutableListOf<AutocompleteMetricEvent>()
        val metrics = AutocompleteMetrics(
            isEnabled = { false },
            sink = emitted::add,
        )

        metrics.record(AutocompleteMetricType.INLINE_CACHE_HIT, "count" to 2)

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun emitsStructuredEventsWhenEnabled() {
        val emitted = mutableListOf<AutocompleteMetricEvent>()
        val metrics = AutocompleteMetrics(
            isEnabled = { true },
            sink = emitted::add,
        )

        metrics.record(
            AutocompleteMetricType.ACCEPTED_TEXT_REVERTED,
            "kind" to "inline",
            "deleted_chars" to 3,
        )

        assertEquals(1, emitted.size)
        assertEquals(AutocompleteMetricType.ACCEPTED_TEXT_REVERTED, emitted.single().type)
        assertEquals(listOf("kind" to "inline", "deleted_chars" to 3), emitted.single().fields)
    }
}
