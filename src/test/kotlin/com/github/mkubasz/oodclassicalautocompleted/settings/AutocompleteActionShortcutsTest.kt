package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent

class AutocompleteActionShortcutsTest : BasePlatformTestCase() {
    // Uses PluginSettings.State defaults in plain test code.

    fun testTabAcceptShortcutSetContainsOnlyTab() {
        val shortcuts = AutocompleteActionShortcuts.tabAcceptShortcutSet()
            .shortcuts
            .map { (it as KeyboardShortcut).firstKeyStroke.keyCode }

        assertContainsElements(shortcuts, KeyEvent.VK_TAB)
        assertEquals(1, shortcuts.size)
    }

    fun testInlineAcceptShortcutSetIncludesConfiguredExtras() {
        val shortcuts = AutocompleteActionShortcuts.inlineAcceptShortcutSet(
            PluginSettings.State().apply {
                acceptOnRightArrow = true
                acceptOnEndKey = true
            }
        ).shortcuts.map { (it as KeyboardShortcut).firstKeyStroke.keyCode }

        assertContainsElements(shortcuts, KeyEvent.VK_RIGHT, KeyEvent.VK_END)
    }

    fun testCycleShortcutSetIsEmptyWhenShortcutBlank() {
        val shortcuts = AutocompleteActionShortcuts.cycleNextShortcutSet(
            PluginSettings.State().apply {
                cycleNextShortcut = ""
            }
        ).shortcuts

        assertEquals(0, shortcuts.size)
    }

    fun testValidateShortcutAcceptsPluginStyleSyntax() {
        AutocompleteActionShortcuts.validateShortcut("alt CLOSE_BRACKET", "Next alternative shortcut")
        AutocompleteActionShortcuts.validateShortcut("meta alt PERIOD", "Next alternative shortcut")
    }

    fun testNormalizeShortcutAcceptsFriendlyMacSyntax() {
        assertEquals("meta Y", AutocompleteActionShortcuts.normalizeShortcut("meta y", "Next alternative shortcut"))
        assertEquals("meta SEMICOLON", AutocompleteActionShortcuts.normalizeShortcut("cmd+;", "Next alternative shortcut"))
        assertEquals("meta CLOSE_BRACKET", AutocompleteActionShortcuts.normalizeShortcut("cmd+]", "Next alternative shortcut"))
    }

    fun testValidateShortcutRejectsInvalidSyntax() {
        val error = try {
            AutocompleteActionShortcuts.validateShortcut("alt", "Next alternative shortcut")
            fail("Expected IllegalArgumentException")
            return
        } catch (e: IllegalArgumentException) {
            e
        }

        assertTrue((error.message ?: "").contains("Next alternative shortcut is invalid"))
    }
}
