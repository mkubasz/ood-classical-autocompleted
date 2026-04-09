package com.github.mkubasz.oodclassicalautocompleted.settings

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut

internal object AutocompleteActionShortcuts {

    const val ACCEPT_ACTION_ID = "OodAutocomplete.AcceptCompletion"
    const val ACCEPT_INLINE_ACTION_ID = "OodAutocomplete.AcceptInlineSuggestion"
    const val REJECT_ACTION_ID = "OodAutocomplete.RejectCompletion"
    const val ACCEPT_NEXT_WORD_ACTION_ID = "OodAutocomplete.AcceptNextWord"
    const val ACCEPT_NEXT_LINE_ACTION_ID = "OodAutocomplete.AcceptNextLine"
    const val CYCLE_NEXT_ACTION_ID = "OodAutocomplete.CycleNextSuggestion"
    const val CYCLE_PREVIOUS_ACTION_ID = "OodAutocomplete.CyclePreviousSuggestion"

    fun tabAcceptShortcutSet(): CustomShortcutSet =
        CustomShortcutSet.fromString("TAB")

    fun inlineAcceptShortcutSet(state: PluginSettings.State): CustomShortcutSet =
        CustomShortcutSet.fromStrings(
            buildList {
                if (state.acceptOnRightArrow) {
                    add("RIGHT")
                }
                if (state.acceptOnEndKey) {
                    add("END")
                }
            }
        )

    fun acceptNextWordShortcutSet(): CustomShortcutSet =
        CustomShortcutSet.fromString("ctrl RIGHT")

    fun acceptNextLineShortcutSet(): CustomShortcutSet =
        CustomShortcutSet.fromString("ctrl shift RIGHT")

    fun rejectShortcutSet(): CustomShortcutSet =
        CustomShortcutSet.fromString("ESCAPE")

    fun cycleNextShortcutSet(state: PluginSettings.State): CustomShortcutSet =
        CustomShortcutSet.fromStrings(
            listOfNotNull(
                parseShortcutOrNull(state.cycleNextShortcut)
            )
        )

    fun cyclePreviousShortcutSet(state: PluginSettings.State): CustomShortcutSet =
        CustomShortcutSet.fromStrings(
            listOfNotNull(
                parseShortcutOrNull(state.cyclePreviousShortcut)
            )
        )

    fun normalizeShortcut(rawValue: String, fieldName: String): String {
        val normalized = parseShortcutOrNull(rawValue) ?: return ""
        if (normalized.split(WHITESPACE_REGEX).none { it in MODIFIER_TOKENS }) {
            throw IllegalArgumentException(
                "$fieldName is invalid: '$rawValue'. Examples: 'cmd+y', 'cmd+;', 'cmd+]', or 'ctrl+alt+.'.",
            )
        }

        try {
            KeyboardShortcut.fromString(normalized)
        } catch (_: Throwable) {
            throw IllegalArgumentException(
                "$fieldName is invalid: '$rawValue'. Examples: 'cmd+y', 'cmd+;', 'cmd+]', or 'ctrl+alt+.'.",
            )
        }

        return normalized
    }

    fun validateShortcut(rawValue: String, fieldName: String) {
        normalizeShortcut(rawValue, fieldName)
    }

    private fun parseShortcutOrNull(rawValue: String): String? {
        val normalized = rawValue.trim()
        if (normalized.isEmpty()) return null

        val tokens = normalized
            .replace("+", " ")
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return null

        return tokens.joinToString(" ") { token ->
            TOKEN_ALIASES[token.lowercase()] ?: when {
                token.length == 1 && token[0].isLetterOrDigit() -> token.uppercase()
                else -> token.uppercase()
            }
        }
    }

    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val MODIFIER_TOKENS = setOf("meta", "alt", "ctrl", "shift")

    private val TOKEN_ALIASES = mapOf(
        "meta" to "meta",
        "cmd" to "meta",
        "command" to "meta",
        "⌘" to "meta",
        "alt" to "alt",
        "opt" to "alt",
        "option" to "alt",
        "⌥" to "alt",
        "ctrl" to "ctrl",
        "control" to "ctrl",
        "ctl" to "ctrl",
        "⌃" to "ctrl",
        "shift" to "shift",
        "⇧" to "shift",
        "esc" to "ESCAPE",
        "escape" to "ESCAPE",
        "enter" to "ENTER",
        "return" to "ENTER",
        "up" to "UP",
        "down" to "DOWN",
        "left" to "LEFT",
        "right" to "RIGHT",
        ";" to "SEMICOLON",
        "[" to "OPEN_BRACKET",
        "]" to "CLOSE_BRACKET",
        "." to "PERIOD",
        "," to "COMMA",
        "/" to "SLASH",
        "\\" to "BACK_SLASH",
        "'" to "QUOTE",
        "`" to "BACK_QUOTE",
        "-" to "MINUS",
        "=" to "EQUALS",
    )
}
