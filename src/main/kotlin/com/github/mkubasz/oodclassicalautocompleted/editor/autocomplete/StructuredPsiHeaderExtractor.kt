package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

internal object StructuredPsiHeaderExtractor {

    fun extract(
        project: Project?,
        text: String,
        filePath: String?,
    ): List<String> {
        val normalizedText = text.replace("\r", "")
        if (project == null || project.isDisposed) {
            return extractHeuristically(normalizedText)
        }

        return runCatching {
            ApplicationManager.getApplication().runReadAction<List<String>> {
                val psiFile = TemporaryPsiFileSupport.createTemporaryPsiFile(project, normalizedText, filePath)
                    ?: return@runReadAction extractHeuristically(normalizedText)

                val headers = linkedSetOf<String>()
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                        extractStructuredHeader(element)?.let(headers::add)
                        super.visitElement(element)
                    }
                })

                headers.ifEmpty { extractHeuristically(normalizedText) }.toList()
            }
        }.getOrElse { extractHeuristically(normalizedText) }
    }

    private fun extractStructuredHeader(element: com.intellij.psi.PsiElement): String? {
        val kind = element.javaClass.simpleName

        return when {
            looksLikeDecoratorOrAnnotation(kind) -> normalizeHeaderLine(element.text)
            element is PsiNamedElement && looksLikeStructuredDeclaration(kind) -> extractDeclarationHeader(element.text)
            else -> null
        }
    }

    private fun looksLikeDecoratorOrAnnotation(kind: String): Boolean =
        kind.contains("Decorator", ignoreCase = true) ||
            kind.contains("Annotation", ignoreCase = true)

    private fun looksLikeStructuredDeclaration(kind: String): Boolean =
        STRUCTURED_DECLARATION_MARKERS.any { marker ->
            kind.contains(marker, ignoreCase = true)
        }

    private fun extractDeclarationHeader(text: String): String? = text.replace("\r", "")
        .lineSequence()
        .map(String::trim)
        .dropWhile(String::isBlank)
        .firstOrNull { !it.startsWith("@") }
        ?.takeIf(String::isNotBlank)

    private fun normalizeHeaderLine(text: String): String? = text.replace("\r", "")
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)

    private fun extractHeuristically(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter(::looksLikeStructuredHeader)
        .toList()

    private fun looksLikeStructuredHeader(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.startsWith("@")) return true

        val terminal = line.last()
        if (terminal != ':' && terminal != '{') return false

        val head = line.removeSuffix(terminal.toString()).trimEnd()
        if (head.isBlank()) return false

        val openingParens = head.count { it == '(' }
        val closingParens = head.count { it == ')' }
        val looksCallable = '(' in head && openingParens >= closingParens
        val looksTypedDeclaration = ':' in head || "->" in head
        val looksNamedDeclaration = head.any(Char::isLetterOrDigit)

        return looksCallable || looksTypedDeclaration || looksNamedDeclaration
    }

    private val STRUCTURED_DECLARATION_MARKERS = listOf(
        "Function",
        "Method",
        "Class",
        "Interface",
        "Struct",
        "Enum",
        "Object",
        "Property",
        "TypeAlias",
    )
}
