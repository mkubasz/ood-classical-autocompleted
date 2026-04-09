package com.github.mkubasz.oodclassicalautocompleted.evaluation

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunk
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.RetrievedContextChunkKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineInlineEvaluationRunnerTest {

    @Test
    fun summarizesReplayEvaluationAcrossCases() = runBlocking {
        val suite = OfflineInlineEvaluationSuite(
            cases = listOf(
                OfflineInlineEvaluationCase(
                    id = "perfect",
                    document = "fun main() {\n    pri<|cursor|>\n}",
                    expected = "ntln(\"hi\")",
                    language = "kotlin",
                ),
                OfflineInlineEvaluationCase(
                    id = "partial",
                    document = "return <|cursor|>",
                    expected = "value + 1",
                    language = "python",
                    retrievedChunks = listOf(
                        RetrievedContextChunk(
                            filePath = "/repo/math_helpers.py",
                            content = "def value_plus_one(value):\n    return value + 1",
                            score = 4.2,
                            chunkKind = RetrievedContextChunkKind.SYMBOL,
                            symbolName = "value_plus_one",
                            language = "python",
                        )
                    ),
                    expectedRetrievedFiles = listOf("math_helpers.py"),
                    expectedRetrievedSymbols = listOf("value_plus_one"),
                ),
                OfflineInlineEvaluationCase(
                    id = "miss",
                    document = "const answer = <|cursor|>",
                    expected = "42",
                    language = "javascript",
                ),
            )
        )
        val engine = ReplayInlineEvaluationEngine(
            OfflineInlineEvaluationReplay(
                predictions = listOf(
                    OfflineInlineEvaluationReplayPrediction("perfect", "ntln(\"hi\")", latencyMs = 20),
                    OfflineInlineEvaluationReplayPrediction("partial", "value", latencyMs = 50),
                    OfflineInlineEvaluationReplayPrediction("miss", "0", latencyMs = 100),
                )
            )
        )

        val report = OfflineInlineEvaluationRunner.evaluate(suite, engine)

        assertEquals(3, report.summary.caseCount)
        assertEquals(1.0 / 3.0, report.summary.exactMatchRate, 0.0001)
        assertEquals(50L, report.summary.latencyMsP50)
        assertEquals(100L, report.summary.latencyMsP90)
        assertEquals(100L, report.summary.latencyMsP95)
        assertEquals(1.0, report.summary.retrievalFileHitRate ?: 0.0, 0.0001)
        assertEquals(1.0, report.summary.retrievalSymbolHitRate ?: 0.0, 0.0001)
        assertNotNull(report.summary.retrievalMatchedRatioDeltaMean)
        assertNotNull(report.summary.retrievalLatencyDeltaMeanMs)
        assertEquals(1, report.cases.single { it.id == "perfect" }.perfectLines)
        assertTrue(report.cases.single { it.id == "partial" }.matchedRatio in 0.5..0.6)
        assertEquals(1, report.cases.single { it.id == "partial" }.retrievalMatchedFiles)
        assertEquals(1, report.cases.single { it.id == "partial" }.retrievalMatchedSymbols)
    }

    @Test
    fun parsesCursorMarkedDocumentExactlyOnce() {
        val split = CursorMarkedDocument.parse("abc<|cursor|>xyz")

        assertEquals("abc", split.prefix)
        assertEquals("xyz", split.suffix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDocumentsWithoutCursorMarker() {
        CursorMarkedDocument.parse("abcxyz")
    }
}
