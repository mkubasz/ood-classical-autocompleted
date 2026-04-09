package com.github.mkubasz.oodclassicalautocompleted.settings

import com.github.mkubasz.oodclassicalautocompleted.completion.providers.inception.InceptionLabsNextEditContextOptions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class InceptionLabsAdvancedSettingsTest : BasePlatformTestCase() {
    // Uses PluginSettings.State defaults in plain test code.

    fun testBuildsFimOptionsFromValidatedState() {
        val state = PluginSettings.State().apply {
            inceptionLabsFimMaxTokens = 256
            inceptionLabsFimPresencePenalty = 1.2
            inceptionLabsFimTemperature = 0.1
            inceptionLabsFimTopP = 0.7
            inceptionLabsFimStopSequences = "stop-a\n\nstop-b"
            inceptionLabsFimExtraBodyJson = """{"reasoning_effort":"low"}"""
        }

        val options = InceptionLabsAdvancedSettings.fimOptionsFromState(state)

        assertEquals(256, options.maxTokens)
        assertEquals(1.2, options.presencePenalty)
        assertEquals(0.1, options.temperature)
        assertEquals(0.7, options.topP)
        assertEquals(listOf("stop-a", "stop-b"), options.stopSequences)
        assertEquals("low", options.extraBodyJson?.get("reasoning_effort")?.toString()?.trim('"'))
    }

    fun testRejectsTooManyStopSequences() {
        val error = expectIllegalArgument {
            InceptionLabsAdvancedSettings.parseStopSequences(
                rawValue = "a\nb\nc\nd\ne",
                fieldName = "Stops",
            )
        }

        assertTrue(error.contains("at most 4"))
    }

    fun testRejectsReservedExtraJsonKeys() {
        val error = expectIllegalArgument {
            InceptionLabsAdvancedSettings.parseExtraBodyJson(
                rawValue = """{"diffusing":true}""",
                fieldName = "Extra JSON",
                disallowedKeys = setOf("diffusing"),
            )
        }

        assertTrue(error.contains("diffusing"))
    }

    fun testRejectsNonObjectExtraJson() {
        val error = expectIllegalArgument {
            InceptionLabsAdvancedSettings.parseExtraBodyJson(
                rawValue = """["bad"]""",
                fieldName = "Extra JSON",
                disallowedKeys = emptySet(),
            )
        }

        assertTrue(error.contains("JSON object"))
    }

    fun testRejectsOutOfRangeTemperature() {
        val error = expectIllegalArgument {
            InceptionLabsAdvancedSettings.validateState(
                PluginSettings.State().apply {
                    inceptionLabsNextEditTemperature = 1.2
                }
            )
        }

        assertTrue(error.contains("between 0 and 1"))
    }

    fun testBuildsNextEditContextOptions() {
        val options = InceptionLabsAdvancedSettings.nextEditContextOptionsFromState(
            PluginSettings.State().apply {
                inceptionLabsNextEditLinesAboveCursor = 7
                inceptionLabsNextEditLinesBelowCursor = 12
            }
        )

        assertEquals(7, options.linesAboveCursor)
        assertEquals(12, options.linesBelowCursor)
    }

    fun testNextEditContextDefaultsMatchDocs() {
        val options = InceptionLabsAdvancedSettings.nextEditContextOptionsFromState(PluginSettings.State())

        assertEquals(InceptionLabsNextEditContextOptions.DEFAULT_LINES_ABOVE_CURSOR, options.linesAboveCursor)
        assertEquals(InceptionLabsNextEditContextOptions.DEFAULT_LINES_BELOW_CURSOR, options.linesBelowCursor)
    }

    private fun expectIllegalArgument(block: () -> Unit): String {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error.message.orEmpty()
        }
        fail("Expected IllegalArgumentException")
        return ""
    }
}
