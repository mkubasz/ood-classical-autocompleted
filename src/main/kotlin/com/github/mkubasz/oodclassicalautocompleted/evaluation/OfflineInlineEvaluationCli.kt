package com.github.mkubasz.oodclassicalautocompleted.evaluation

import kotlinx.coroutines.runBlocking
import kotlin.io.path.Path

fun main(args: Array<String>) {
    runBlocking {
        val options = parseOfflineInlineEvaluationArgs(args)
        val suite = OfflineInlineEvaluationJson.loadSuite(options.casesPath)
        OfflineInlineEvaluationEngineFactory.create(options).use { engine ->
            val report = OfflineInlineEvaluationRunner.evaluate(suite, engine)
            println(OfflineInlineEvaluationFormatter.humanSummary(report))
            options.outputPath?.let { outputPath ->
                OfflineInlineEvaluationJson.writeReport(outputPath, report)
                println("report=${outputPath.toAbsolutePath()}")
            }
        }
    }
}

internal fun parseOfflineInlineEvaluationArgs(args: Array<String>): OfflineInlineEvaluationCliOptions {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val argument = args[index]
        require(argument.startsWith("--")) { "Unexpected argument: $argument" }
        val stripped = argument.removePrefix("--")
        val separator = stripped.indexOf('=')
        if (separator >= 0) {
            values[stripped.substring(0, separator)] = stripped.substring(separator + 1)
            index++
            continue
        }
        require(index + 1 < args.size) { "Missing value for --$stripped" }
        values[stripped] = args[index + 1]
        index += 2
    }

    val casesPath = values["cases"]?.let(::Path)
        ?: throw IllegalArgumentException("Missing required --cases path")

    return OfflineInlineEvaluationCliOptions(
        casesPath = casesPath,
        provider = values["provider"]?.let { parseProvider(it) } ?: OfflineInlineEvaluationProvider.REPLAY,
        predictionsPath = values["predictions"]?.let(::Path),
        outputPath = values["output"]?.let(::Path),
        apiKey = values["api-key"],
        baseUrl = values["base-url"],
        model = values["model"],
        contextBudgetChars = values["context-budget-chars"]?.toIntOrNull() ?: DEFAULT_CONTEXT_BUDGET_CHARS,
        minConfidenceScore = values["min-confidence-score"]?.toDoubleOrNull() ?: 0.0,
    )
}

private fun parseProvider(raw: String): OfflineInlineEvaluationProvider =
    when (raw.lowercase()) {
        "replay" -> OfflineInlineEvaluationProvider.REPLAY
        "anthropic" -> OfflineInlineEvaluationProvider.ANTHROPIC
        "inception", "inception_labs", "inception-labs" -> OfflineInlineEvaluationProvider.INCEPTION
        else -> throw IllegalArgumentException("Unsupported provider: $raw")
    }
