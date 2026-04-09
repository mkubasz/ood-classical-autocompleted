package com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete

internal object InceptionLabsFimStopSequencePolicy {

    fun merge(
        request: AutocompleteRequest,
        configuredStops: List<String>,
    ): List<String> {
        val stops = linkedSetOf<String>()
        stops += configuredStops
        if (shouldStaySingleLine(request)) {
            stops += "\n"
            stops += "\r\n"
        }
        return stops.toList()
    }

    private fun shouldStaySingleLine(request: AutocompleteRequest): Boolean {
        val context = request.inlineContext
        if (context?.isAfterMemberAccess == true) return true
        if (context?.isInParameterListLikeContext == true) return true
        if (context?.isDefinitionHeaderLikeContext == true) return true

        val linePrefix = request.prefix.substringAfterLast('\n').trimEnd()
        val lineSuffix = request.suffix.substringBefore('\n').trimStart()
        return linePrefix.isNotBlank() && lineSuffix.isNotBlank()
    }
}
