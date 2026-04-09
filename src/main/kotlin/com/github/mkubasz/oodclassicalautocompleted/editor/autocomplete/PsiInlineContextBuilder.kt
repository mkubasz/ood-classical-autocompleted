package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineLexicalContext
import com.github.mkubasz.oodclassicalautocompleted.core.api.autocomplete.InlineModelContext
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement

internal object PsiInlineContextBuilder {

    fun build(
        project: Project,
        document: Document,
        documentText: String,
        caretOffset: Int,
    ): InlineModelContext? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val contextLeaf = findContextLeaf(psiFile, caretOffset) ?: return null
        val parentChain = generateSequence(contextLeaf) { it.parent }.take(MAX_PARENT_DEPTH).toList()
        val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(contextLeaf.language)
        val lexicalContext = classifyLexicalContext(contextLeaf, parserDefinition)
        val resolvedReference = resolveRelevantReference(psiFile, caretOffset, contextLeaf)
        val currentLinePrefix = documentText.substring(0, caretOffset.coerceIn(0, documentText.length)).substringAfterLast('\n')
        val isClassBaseListLikeContext = isClassBaseListLikeContext(currentLinePrefix)
        val classBaseReferencePrefix = extractClassBaseReferencePrefix(currentLinePrefix)
        val matchingTypeNames = if (isClassBaseListLikeContext) {
            findTypeNamesMatchingPrefix(
                psiFile = psiFile,
                documentText = documentText,
                prefix = classBaseReferencePrefix,
            )
        } else {
            emptyList()
        }
        val receiverExpression = ReceiverContextHeuristics.extractReceiverExpression(currentLinePrefix)
        val receiverMemberNames = receiverExpression
            ?.let {
                resolveReceiverMembers(
                    psiFile = psiFile,
                    documentText = documentText,
                    caretOffset = caretOffset,
                    parentChain = parentChain,
                    receiverExpression = it,
                )
            }
            .orEmpty()

        return InlineModelContext(
            lexicalContext = lexicalContext,
            enclosingNames = parentChain
                .mapNotNull { (it as? PsiNamedElement)?.name?.takeIf(String::isNotBlank) }
                .distinct()
                .take(MAX_ENCLOSING_NAMES),
            enclosingKinds = parentChain
                .map { it.javaClass.simpleName }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_ENCLOSING_KINDS),
            isDecoratorLikeContext = isDecoratorLikeContext(contextLeaf, parentChain),
            isClassBaseListLikeContext = isClassBaseListLikeContext,
            isAfterMemberAccess = currentLinePrefix.trimEnd().endsWith("."),
            receiverExpression = receiverExpression,
            receiverMemberNames = receiverMemberNames,
            isInParameterListLikeContext = isParameterListLikeContext(currentLinePrefix, parentChain),
            isDefinitionHeaderLikeContext = isDefinitionHeaderLikeContext(currentLinePrefix, parentChain),
            classBaseReferencePrefix = classBaseReferencePrefix,
            matchingTypeNames = matchingTypeNames,
            resolvedReferenceName = resolvedReference?.name,
            resolvedFilePath = resolvedReference?.filePath,
            resolvedSnippet = resolvedReference?.snippet,
        ).takeIf { it.hasUsefulSignal() }
    }

    private fun findContextLeaf(psiFile: PsiFile, caretOffset: Int): PsiElement? {
        if (psiFile.textLength == 0) return null

        val safeOffsets = listOf(
            caretOffset.coerceAtMost(psiFile.textLength - 1),
            (caretOffset - 1).coerceAtLeast(0),
            (caretOffset - 2).coerceAtLeast(0),
        ).distinct()

        return safeOffsets.asSequence()
            .mapNotNull(psiFile::findElementAt)
            .firstOrNull()
    }

    private fun classifyLexicalContext(
        contextLeaf: PsiElement,
        parserDefinition: com.intellij.lang.ParserDefinition?,
    ): InlineLexicalContext {
        val tokenType = contextLeaf.node?.elementType ?: return InlineLexicalContext.UNKNOWN
        parserDefinition ?: return InlineLexicalContext.UNKNOWN

        return when {
            parserDefinition.commentTokens.contains(tokenType) -> InlineLexicalContext.COMMENT
            parserDefinition.stringLiteralElements.contains(tokenType) -> InlineLexicalContext.STRING
            else -> InlineLexicalContext.CODE
        }
    }

    private fun isParameterListLikeContext(
        currentLinePrefix: String,
        parentChain: List<PsiElement>,
    ): Boolean {
        val kinds = parentChain.map { it.javaClass.simpleName }
        if (kinds.any { kind ->
                kind.contains("Parameter", ignoreCase = true) ||
                    kind.contains("Argument", ignoreCase = true) ||
                    kind.contains("Call", ignoreCase = true)
            }
        ) {
            return true
        }

        val openParens = currentLinePrefix.count { it == '(' }
        val closeParens = currentLinePrefix.count { it == ')' }
        return openParens > closeParens
    }

    private fun isDecoratorLikeContext(
        contextLeaf: PsiElement,
        parentChain: List<PsiElement>,
    ): Boolean {
        if (contextLeaf.text == "@") return true

        return parentChain
            .map { it.javaClass.simpleName }
            .any { kind ->
                kind.contains("Decorator", ignoreCase = true) ||
                    kind.contains("Annotation", ignoreCase = true)
            }
    }

    private fun isDefinitionHeaderLikeContext(
        currentLinePrefix: String,
        parentChain: List<PsiElement>,
    ): Boolean {
        val trimmed = currentLinePrefix.trimStart()
        if (DEFINITION_PREFIXES.any(trimmed::startsWith)) {
            return true
        }

        return parentChain
            .map { it.javaClass.simpleName }
            .any { kind ->
                kind.contains("Function", ignoreCase = true) ||
                    kind.contains("Method", ignoreCase = true) ||
                    kind.contains("Class", ignoreCase = true)
            } && currentLinePrefix.contains('(')
    }

    private fun isClassBaseListLikeContext(currentLinePrefix: String): Boolean {
        val trimmed = currentLinePrefix.trimStart()
        if (!trimmed.startsWith("class ")) return false

        val openParens = trimmed.count { it == '(' }
        val closeParens = trimmed.count { it == ')' }
        return openParens > closeParens
    }

    private fun extractClassBaseReferencePrefix(currentLinePrefix: String): String? {
        if (!isClassBaseListLikeContext(currentLinePrefix)) return null

        val segment = currentLinePrefix
            .substringAfterLast('(')
            .substringAfterLast(',')
            .trimStart()

        if (segment.isBlank()) return null

        return CLASS_BASE_REFERENCE_PATTERN.find(segment)?.value
    }

    private fun resolveRelevantReference(
        psiFile: PsiFile,
        caretOffset: Int,
        contextLeaf: PsiElement,
    ): ResolvedReference? {
        val candidateElements = linkedSetOf<PsiElement>()
        listOf(caretOffset, caretOffset - 1, caretOffset - 2)
            .filter { it in 0 until psiFile.textLength }
            .mapNotNull(psiFile::findElementAt)
            .forEach(candidateElements::add)
        candidateElements.add(contextLeaf)

        candidateElements
            .flatMap { generateSequence(it) { parent -> parent.parent }.take(MAX_REFERENCE_PARENT_DEPTH).toList() }
            .forEach(candidateElements::add)

        candidateElements.forEach { candidate ->
            candidate.references.forEach { reference ->
                val resolved = runCatching { reference.resolve() }.getOrNull() ?: return@forEach
                val target = resolved.navigationElement
                val snippet = extractSnippet(target)
                if (snippet.isBlank()) return@forEach

                return ResolvedReference(
                    name = (target as? PsiNamedElement)?.name?.takeIf(String::isNotBlank),
                    filePath = target.containingFile?.virtualFile?.path,
                    snippet = snippet,
                )
            }
        }

        return null
    }

    private fun resolveReceiverMembers(
        psiFile: PsiFile,
        documentText: String,
        caretOffset: Int,
        parentChain: List<PsiElement>,
        receiverExpression: String,
    ): List<String> {
        if (ReceiverContextHeuristics.isSelfLike(receiverExpression)) {
            val container = parentChain.firstOrNull(::isTypeLikeContainer) ?: return emptyList()
            return collectMemberNames(container)
        }

        val receiverLeaf = findReceiverLeaf(
            psiFile = psiFile,
            documentText = documentText,
            caretOffset = caretOffset,
            receiverExpression = receiverExpression,
        ) ?: return emptyList()

        val resolvedTargets = receiverLeaf.references
            .toMutableList()

        generateSequence(receiverLeaf.parent) { it.parent }
            .take(MAX_REFERENCE_PARENT_DEPTH)
            .flatMap { candidate -> candidate.references.asSequence() }
            .forEach(resolvedTargets::add)

        val resolvedElements = resolvedTargets
            .mapNotNull { reference -> runCatching { reference.resolve() }.getOrNull() }
            .distinct()

        if (resolvedElements.isEmpty()) return emptyList()

        val memberNames = linkedSetOf<String>()
        resolvedElements.forEach { target ->
            if (isTypeLikeContainer(target)) {
                memberNames += collectMemberNames(target)
            }

            inferTypeName(target)?.let { typeName ->
                findTypeDefinition(target.containingFile ?: psiFile, typeName)?.let { typeElement ->
                    memberNames += collectMemberNames(typeElement)
                }
            }
        }

        return memberNames.take(MAX_RECEIVER_MEMBERS)
    }

    private fun findReceiverLeaf(
        psiFile: PsiFile,
        documentText: String,
        caretOffset: Int,
        receiverExpression: String,
    ): PsiElement? {
        val prefix = documentText.substring(0, caretOffset.coerceIn(0, documentText.length))
        val receiverWithDot = "$receiverExpression."
        val receiverStart = prefix.lastIndexOf(receiverWithDot)
        if (receiverStart < 0) return null

        val receiverName = receiverExpression.substringAfterLast('.')
        val nameStart = receiverStart + receiverExpression.length - receiverName.length
        val candidateOffsets = listOf(nameStart, nameStart + receiverName.length - 1)
            .map { it.coerceIn(0, (psiFile.textLength - 1).coerceAtLeast(0)) }
            .distinct()

        return candidateOffsets.asSequence()
            .mapNotNull(psiFile::findElementAt)
            .firstOrNull { it.text == receiverName || it.parent?.text == receiverName }
            ?: candidateOffsets.asSequence()
                .mapNotNull(psiFile::findElementAt)
                .map { it.parent ?: it }
                .firstOrNull()
    }

    private fun collectMemberNames(container: PsiElement): List<String> {
        val containerName = (container as? PsiNamedElement)?.name
        val memberNames = linkedSetOf<String>()
        collectNamedMembers(container, depth = 0, memberNames = memberNames, containerName = containerName)
        return memberNames.take(MAX_RECEIVER_MEMBERS)
    }

    private fun collectNamedMembers(
        element: PsiElement,
        depth: Int,
        memberNames: LinkedHashSet<String>,
        containerName: String?,
    ) {
        if (depth > MAX_MEMBER_COLLECTION_DEPTH || memberNames.size >= MAX_RECEIVER_MEMBERS) return

        element.children.forEach { child ->
            val name = (child as? PsiNamedElement)?.name
            if (!name.isNullOrBlank() &&
                name != containerName &&
                !looksLikeContainerNameOnly(child) &&
                isMemberLikeElement(child)
            ) {
                memberNames += name
            }
            collectNamedMembers(child, depth + 1, memberNames, containerName)
        }
    }

    private fun inferTypeName(target: PsiElement): String? {
        val text = target.text.replace("\r", " ").replace("\n", " ")
        val patterns = listOf(
            Regex("""\b([A-Z][A-Za-z0-9_]*)\s+[A-Za-z_][A-Za-z0-9_]*\s*(=|;|,|\))"""),
            Regex("""=\s*([A-Z][A-Za-z0-9_]*)\s*\("""),
            Regex(""":\s*([A-Z][A-Za-z0-9_]*)"""),
        )

        return patterns.asSequence()
            .mapNotNull { pattern -> pattern.find(text)?.groupValues?.getOrNull(1) }
            .firstOrNull()
    }

    private fun findTypeDefinition(file: PsiFile, typeName: String): PsiElement? {
        val queue = ArrayDeque<PsiElement>()
        queue += file
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current !== file &&
                isTypeLikeContainer(current) &&
                (current as? PsiNamedElement)?.name == typeName
            ) {
                return current
            }
            current.children.forEach(queue::addLast)
        }
        return null
    }

    private fun findTypeNamesMatchingPrefix(
        psiFile: PsiFile,
        documentText: String,
        prefix: String?,
    ): List<String> {
        if (prefix.isNullOrBlank()) return emptyList()

        val names = linkedSetOf<String>()
        val queue = ArrayDeque<PsiElement>()
        queue += psiFile
        while (queue.isNotEmpty() && names.size < MAX_MATCHING_TYPES) {
            val current = queue.removeFirst()
            if (current !== psiFile && isTypeLikeContainer(current)) {
                val name = (current as? PsiNamedElement)?.name
                if (!name.isNullOrBlank() && name.startsWith(prefix, ignoreCase = true) && name != prefix) {
                    names += name
                }
            }
            current.children.forEach(queue::addLast)
        }

        if (names.size >= MAX_MATCHING_TYPES) return names.toList()

        CLASS_DECLARATION_PATTERN.findAll(documentText)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }
            .forEach(names::add)

        return names.take(MAX_MATCHING_TYPES)
    }

    private fun isTypeLikeContainer(element: PsiElement): Boolean {
        val kind = element.javaClass.simpleName
        val name = (element as? PsiNamedElement)?.name?.takeIf(String::isNotBlank) ?: return false
        return name.isNotBlank() && (
            kind.contains("Class", ignoreCase = true) ||
            kind.contains("Interface", ignoreCase = true) ||
            kind.contains("Struct", ignoreCase = true) ||
            kind.contains("Object", ignoreCase = true) ||
            kind.contains("Enum", ignoreCase = true)
            )
    }

    private fun isMemberLikeElement(element: PsiElement): Boolean {
        val kind = element.javaClass.simpleName
        return kind.contains("Method", ignoreCase = true) ||
            kind.contains("Function", ignoreCase = true) ||
            kind.contains("Field", ignoreCase = true) ||
            kind.contains("Property", ignoreCase = true) ||
            kind.contains("Parameter", ignoreCase = true) ||
            kind.contains("Variable", ignoreCase = true) ||
            element.parent?.let(::isTypeLikeContainer) == true
    }

    private fun looksLikeContainerNameOnly(element: PsiElement): Boolean = isTypeLikeContainer(element)

    private fun extractSnippet(element: PsiElement): String {
        val lines = element.text
            .replace("\r", "")
            .lineSequence()
            .map(String::trimEnd)
            .dropWhile(String::isBlank)
            .take(MAX_SNIPPET_LINES)
            .toList()

        if (lines.isEmpty()) return ""

        return lines.joinToString("\n")
            .take(MAX_SNIPPET_CHARS)
            .trim()
    }

    private fun InlineModelContext.hasUsefulSignal(): Boolean =
        lexicalContext != InlineLexicalContext.UNKNOWN ||
            enclosingNames.isNotEmpty() ||
            enclosingKinds.isNotEmpty() ||
            isDecoratorLikeContext ||
            isClassBaseListLikeContext ||
            isAfterMemberAccess ||
            !receiverExpression.isNullOrBlank() ||
            receiverMemberNames.isNotEmpty() ||
            isInParameterListLikeContext ||
            isDefinitionHeaderLikeContext ||
            !classBaseReferencePrefix.isNullOrBlank() ||
            matchingTypeNames.isNotEmpty() ||
            !resolvedReferenceName.isNullOrBlank() ||
            !resolvedSnippet.isNullOrBlank()

    private data class ResolvedReference(
        val name: String?,
        val filePath: String?,
        val snippet: String,
    )

    private val DEFINITION_PREFIXES = listOf("def ", "fun ", "function ", "class ", "interface ", "struct ", "enum ")
    private const val MAX_PARENT_DEPTH = 8
    private const val MAX_REFERENCE_PARENT_DEPTH = 6
    private const val MAX_ENCLOSING_NAMES = 4
    private const val MAX_ENCLOSING_KINDS = 6
    private const val MAX_RECEIVER_MEMBERS = 12
    private const val MAX_MATCHING_TYPES = 12
    private const val MAX_MEMBER_COLLECTION_DEPTH = 4
    private const val MAX_SNIPPET_LINES = 8
    private const val MAX_SNIPPET_CHARS = 500
    private val CLASS_BASE_REFERENCE_PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_\.]*$""")
    private val CLASS_DECLARATION_PATTERN = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)""")
}
