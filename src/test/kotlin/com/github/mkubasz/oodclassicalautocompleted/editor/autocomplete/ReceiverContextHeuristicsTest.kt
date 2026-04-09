package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverContextHeuristicsTest {

    @Test
    fun extractsSimpleReceiverExpression() {
        assertEquals("self", ReceiverContextHeuristics.extractReceiverExpression("    self."))
    }

    @Test
    fun extractsChainedReceiverExpression() {
        assertEquals("client.session", ReceiverContextHeuristics.extractReceiverExpression("client.session."))
    }

    @Test
    fun ignoresNonMemberAccessPrefixes() {
        assertNull(ReceiverContextHeuristics.extractReceiverExpression("logger.addHandler(handler)"))
    }
}
