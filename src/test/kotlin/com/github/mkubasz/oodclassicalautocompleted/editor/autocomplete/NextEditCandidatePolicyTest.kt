package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.NextEditCompletionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEditCandidatePolicyTest {

    @Test
    fun testRejectsSingleLineInsertionAtCaretForPreview() {
        val snapshot = snapshotFor("def clear_memory(self) -> None:\n    self.")
        val candidate = NextEditCompletionCandidate(
            startOffset = snapshot.caretOffset,
            endOffset = snapshot.caretOffset,
            replacementText = "_memory.clear()",
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertNull(preview)
    }

    @Test
    fun testAllowsMultilineInsertionAtCaretForPreview() {
        val snapshot = snapshotFor(
            "a = lambda x: x  # noqa: E731\nclass A:\n    def __init__(self):\n        pass\n\n    def a(self,"
        )
        val candidate = NextEditCompletionCandidate(
            startOffset = snapshot.caretOffset,
            endOffset = snapshot.caretOffset,
            replacementText = "):\n        pass",
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertEquals(candidate, preview)
    }

    @Test
    fun testRejectsPreviewWhileTypingDecoratorLine() {
        val snapshot = snapshotFor("class Agent:\n    @property")
        val candidate = NextEditCompletionCandidate(
            startOffset = 0,
            endOffset = snapshot.documentText.length,
            replacementText = "class Agent:\n    @property\n    def history(self) -> History:\n        return self._history",
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertNull(preview)
    }

    @Test
    fun testRejectsPreviewWhileTypingPropertySetterSignature() {
        val prefix = """
            @property
            def history(self) -> History:
                return self._history

            @history.setter
            def history(self
        """.trimIndent()
        val snapshot = snapshotFor(
            prefix = prefix,
            inlineContext = InlineModelContext(
                lexicalContext = InlineLexicalContext.CODE,
                isInParameterListLikeContext = true,
                isDefinitionHeaderLikeContext = true,
            ),
        )
        val candidate = NextEditCompletionCandidate(
            startOffset = 0,
            endOffset = snapshot.documentText.length,
            replacementText = prefix + ", value: History) -> None:\n        self._history = value",
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertNull(preview)
    }

    @Test
    fun testRejectsDuplicateStructuredBlockAlreadyPresentBelowCaret() {
        val prefix = """
            class Ania:
                def __init__(self, name, age):
                    self.name = name
                    self.age = age

        """.trimIndent()
        val suffix = """
                def introduce(self):
                    return f"My name is {self.name} and I am {self.age} years old."

                @property
                def is_adult(self) -> bool:
                    return self.age >= 18
        """.trimIndent()
        val snapshot = snapshotFor(prefix = prefix, suffix = "\n$suffix")
        val candidate = NextEditCompletionCandidate(
            startOffset = prefix.length,
            endOffset = prefix.length,
            replacementText = "\n$suffix",
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertNull(preview)
    }

    @Test
    fun testRejectsNearDuplicateStructuredBlockWhenHeadersAlreadyExistNearby() {
        val prefix = """
            class Ania:
                def __init__(self, name, age):
                    self.name = name
                    self.age = age

        """.trimIndent()
        val suffix = """
                def introduce(self):
                    return f"My name is {self.name} and I am {self.age} years old."

                @property
                def is_adult(self) -> bool:
                    return self.age >= 18
        """.trimIndent()
        val snapshot = snapshotFor(prefix = prefix, suffix = "\n$suffix")
        val candidate = NextEditCompletionCandidate(
            startOffset = prefix.length,
            endOffset = prefix.length,
            replacementText = """

                def introduce(self):
                    return f"Name: {self.name}, age: {self.age}"

                @property
                def is_adult(self) -> bool:
                    return self.age >= 18
            """.trimIndent(),
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertNull(preview)
    }

    @Test
    fun testAllowsUniqueStructuredBlockWhenItDoesNotDuplicateNearbyCode() {
        val prefix = """
            class Ania:
                def __init__(self, name, age):
                    self.name = name
                    self.age = age

        """.trimIndent()
        val suffix = """
                def introduce(self):
                    return f"My name is {self.name} and I am {self.age} years old."
        """.trimIndent()
        val snapshot = snapshotFor(prefix = prefix, suffix = "\n$suffix")
        val candidate = NextEditCompletionCandidate(
            startOffset = prefix.length,
            endOffset = prefix.length,
            replacementText = """

                def full_name(self) -> str:
                    return self.name
            """.trimIndent(),
        )

        val preview = NextEditCandidatePolicy.previewCandidateOrNull(
            candidate = candidate,
            snapshot = snapshot,
            maxPreviewLines = 20,
            maxPreviewChars = 800,
        )

        assertEquals(candidate, preview)
    }

    private fun snapshotFor(
        prefix: String,
        suffix: String = "",
        inlineContext: InlineModelContext? = null,
    ): CompletionContextSnapshot = CompletionContextSnapshot(
        filePath = "agent.py",
        language = "Python",
        documentText = prefix + suffix,
        documentStamp = 1L,
        caretOffset = prefix.length,
        prefix = prefix,
        suffix = suffix,
        prefixWindow = prefix,
        suffixWindow = suffix,
        inlineContext = inlineContext,
    )
}
