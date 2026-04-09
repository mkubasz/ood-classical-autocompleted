package com.github.mkubasz.oodclassicalautocompleted.completion.providers.shared

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TokenBoundaryDetectorTest : BasePlatformTestCase() {

    fun testNoHealingWhenCaretIsAtTokenBoundary() {
        val result = TokenBoundaryDetector.heal("console.", "log()")
        assertEquals("console.", result.prefix)
        assertEquals("", result.healedPrefix)
        assertEquals("log()", result.suffix)
    }

    fun testHealsWhenCaretIsMidIdentifier() {
        val result = TokenBoundaryDetector.heal("console.lo", "g()")
        assertEquals("console.", result.prefix)
        assertEquals("lo", result.healedPrefix)
        assertEquals("g()", result.suffix)
    }

    fun testNoHealingWhenSuffixStartsWithNonIdentifier() {
        val result = TokenBoundaryDetector.heal("value", "()")
        assertEquals("value", result.prefix)
        assertEquals("", result.healedPrefix)
        assertEquals("()", result.suffix)
    }

    fun testHealsPartialFunctionName() {
        val result = TokenBoundaryDetector.heal("def proje", "ct_workflow():")
        assertEquals("def ", result.prefix)
        assertEquals("proje", result.healedPrefix)
        assertEquals("ct_workflow():", result.suffix)
    }

    fun testEmptyPrefixNoHealing() {
        val result = TokenBoundaryDetector.heal("", "text")
        assertEquals("", result.prefix)
        assertEquals("", result.healedPrefix)
        assertEquals("text", result.suffix)
    }

    fun testEmptySuffixNoHealing() {
        val result = TokenBoundaryDetector.heal("prefix", "")
        assertEquals("prefix", result.prefix)
        assertEquals("", result.healedPrefix)
        assertEquals("", result.suffix)
    }

    fun testHealsUnderscoreIdentifiers() {
        val result = TokenBoundaryDetector.heal("my_var", "iable = 1")
        assertEquals("", result.prefix)
        assertEquals("my_var", result.healedPrefix)
        assertEquals("iable = 1", result.suffix)
    }
}
