package com.github.mkubasz.oodclassicalautocompleted.editor.autocomplete

import com.github.mkubasz.oodclassicalautocompleted.completion.domain.InlineModelContext
import com.github.mkubasz.oodclassicalautocompleted.completion.domain.ResolvedDefinition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.CompletionContext
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

internal class LspInlineContextProvider(
    private val backend: LspSemanticBackend = JetBrainsLspSemanticBackend,
) : InlineContextProvider {
    override val source: InlineContextSource = InlineContextSource.LSP

    override fun build(request: InlineContextRequest): InlineModelContext? {
        val lsp = backend.collect(request) ?: return null
        val base = BaseInlineContextHeuristics.build(
            request = request,
            includeResolvedDefinitions = false,
            receiverMemberNames = lsp.receiverMemberNames,
        )

        return base.copy(
            enclosingNames = lsp.enclosingNames.ifEmpty { base.enclosingNames },
            enclosingKinds = lsp.enclosingKinds.ifEmpty { base.enclosingKinds },
            resolvedReferenceName = lsp.resolvedReferenceName,
            resolvedFilePath = lsp.resolvedFilePath,
            resolvedSnippet = lsp.resolvedSnippet,
            resolvedDefinitions = lsp.resolvedDefinitions,
        ).takeIf { it.hasUsefulSignal() }
    }
}

internal data class LspSemanticSnapshot(
    val enclosingNames: List<String> = emptyList(),
    val enclosingKinds: List<String> = emptyList(),
    val receiverMemberNames: List<String> = emptyList(),
    val resolvedReferenceName: String? = null,
    val resolvedFilePath: String? = null,
    val resolvedSnippet: String? = null,
    val resolvedDefinitions: List<ResolvedDefinition> = emptyList(),
)

internal interface LspSemanticBackend {
    fun collect(request: InlineContextRequest): LspSemanticSnapshot?
}

internal object JetBrainsLspSemanticBackend : LspSemanticBackend {
    override fun collect(request: InlineContextRequest): LspSemanticSnapshot? {
        val virtualFile = findVirtualFile(request) ?: return null
        val server = findRunningServer(request.project, virtualFile) ?: return null

        val position = offsetToPosition(request.documentText, request.caretOffset)
        val lineContext = BaseInlineContextHeuristics.build(
            request = request,
            includeResolvedDefinitions = false,
        )
        val enclosingSymbols = fetchEnclosingSymbols(server, virtualFile, position)
        val memberNames = if (lineContext.isAfterMemberAccess) {
            fetchCompletionMembers(server, virtualFile, position)
        } else {
            emptyList()
        }
        val definitionSummary = definitionLookupPosition(request, lineContext.receiverExpression)
            ?.let { fetchDefinition(server, virtualFile, it) }

        if (enclosingSymbols.isEmpty() && memberNames.isEmpty() && definitionSummary == null) {
            return null
        }

        return LspSemanticSnapshot(
            enclosingNames = enclosingSymbols.map { it.name },
            enclosingKinds = enclosingSymbols.map { it.kind },
            receiverMemberNames = memberNames,
            resolvedReferenceName = definitionSummary?.name,
            resolvedFilePath = definitionSummary?.filePath,
            resolvedSnippet = definitionSummary?.snippet,
            resolvedDefinitions = definitionSummary
                ?.takeIf { !it.filePath.isNullOrBlank() && !it.snippet.isNullOrBlank() }
                ?.let { listOf(ResolvedDefinition(it.name ?: "definition", it.filePath, it.snippet!!)) }
                .orEmpty(),
        )
    }

    private fun findVirtualFile(request: InlineContextRequest): VirtualFile? =
        FileDocumentManager.getInstance().getFile(request.document)
            ?: request.filePath?.let(LocalFileSystem.getInstance()::findFileByPath)

    private fun findRunningServer(project: Project, file: VirtualFile): LspServer? {
        val manager = LspServerManager.getInstance(project)
        val method = manager.javaClass.methods.firstOrNull {
            it.name == "getServersWithThisFileOpen\$intellij_platform_lsp_impl"
        } ?: return null

        val servers = runCatching { method.invoke(manager, file) as? Collection<*> }
            .getOrNull()
            .orEmpty()
            .filterIsInstance<LspServer>()

        return servers.firstOrNull { it.state == LspServerState.Running }
    }

    private fun fetchEnclosingSymbols(
        server: LspServer,
        file: VirtualFile,
        position: Position,
    ): List<LspEnclosingSymbol> {
        val params = DocumentSymbolParams(server.getDocumentIdentifier(file))
        val response = runCatching {
            server.sendRequestSync<List<Either<SymbolInformation, DocumentSymbol>>>(DOCUMENT_SYMBOL_TIMEOUT_MS) { languageServer ->
                languageServer.textDocumentService.documentSymbol(params)
            }
        }.getOrNull() ?: return emptyList()

        val flatSymbols = response.mapNotNull { either -> either.takeIf { it.isLeft }?.getLeft() }
        val treeSymbols = response.mapNotNull { either -> either.takeIf { it.isRight }?.getRight() }
        return when {
            treeSymbols.isNotEmpty() -> enclosingSymbolsFromTree(treeSymbols, position)
            flatSymbols.isNotEmpty() -> enclosingSymbolsFromFlat(flatSymbols, position)
            else -> emptyList()
        }
    }

    private fun fetchCompletionMembers(
        server: LspServer,
        file: VirtualFile,
        position: Position,
    ): List<String> {
        val params = CompletionParams(
            server.getDocumentIdentifier(file),
            position,
            CompletionContext(CompletionTriggerKind.TriggerCharacter, "."),
        )
        val response = runCatching {
            server.sendRequestSync<Either<List<CompletionItem>, CompletionList>>(COMPLETION_TIMEOUT_MS) { languageServer ->
                languageServer.textDocumentService.completion(params)
            }
        }.getOrNull() ?: return emptyList()

        val items = if (response.isLeft) response.getLeft() else response.getRight()?.items.orEmpty()
        return items
            .mapNotNull { it.label?.takeIf(String::isNotBlank) }
            .distinct()
            .take(MAX_COMPLETION_ITEMS)
    }

    private fun fetchDefinition(
        server: LspServer,
        file: VirtualFile,
        position: Position,
    ): LspResolvedDefinitionSummary? {
        val params = DefinitionParams(server.getDocumentIdentifier(file), position)
        val response = runCatching {
            server.sendRequestSync<Either<List<Location>, List<LocationLink>>>(DEFINITION_TIMEOUT_MS) { languageServer ->
                languageServer.textDocumentService.definition(params)
            }
        }.getOrNull() ?: return null

        val target = if (response.isLeft) {
            response.getLeft().firstOrNull()?.toSummary()
        } else {
            response.getRight().firstOrNull()?.toSummary()
        } ?: return null

        val targetFile = target.filePath?.let(LocalFileSystem.getInstance()::findFileByPath) ?: return target
        val text = runCatching { VfsUtilCore.loadText(targetFile) }.getOrNull() ?: return target
        val startLine = target.startLine.coerceAtLeast(0)
        val lines = text.lineSequence().toList()
        if (startLine >= lines.size) return target

        val snippet = lines
            .drop(startLine)
            .take(MAX_DEFINITION_SNIPPET_LINES)
            .joinToString("\n")
            .trimEnd()
            .take(MAX_DEFINITION_SNIPPET_CHARS)
            .takeIf(String::isNotBlank)

        val name = target.name ?: snippet
            ?.lineSequence()
            ?.firstOrNull()
            ?.let(::extractDefinitionName)

        return target.copy(
            name = name,
            snippet = snippet ?: target.snippet,
        )
    }

    private fun definitionLookupPosition(
        request: InlineContextRequest,
        receiverExpression: String?,
    ): Position? {
        val safeOffset = request.caretOffset.coerceIn(0, request.documentText.length)
        val prefix = request.documentText.substring(0, safeOffset)

        receiverExpression?.takeIf(String::isNotBlank)?.let { receiver ->
            val search = "$receiver."
            val receiverStart = prefix.lastIndexOf(search)
            if (receiverStart >= 0) {
                val offset = receiverStart + receiver.length
                return offsetToPosition(request.documentText, offset)
            }
        }

        val identifier = IDENTIFIER_AT_END.find(prefix)?.value ?: return null
        return offsetToPosition(request.documentText, safeOffset - identifier.length)
    }

    private fun enclosingSymbolsFromTree(
        symbols: List<DocumentSymbol>,
        position: Position,
    ): List<LspEnclosingSymbol> {
        fun path(symbol: DocumentSymbol, trail: List<LspEnclosingSymbol>): List<LspEnclosingSymbol>? {
            if (!positionInRange(position, symbol.range)) return null
            val nextTrail = trail + LspEnclosingSymbol(
                name = symbol.name.orEmpty(),
                kind = symbol.kind?.name ?: "Symbol",
            )
            symbol.children.orEmpty().forEach { child ->
                path(child, nextTrail)?.let { return it }
            }
            return nextTrail
        }

        symbols.forEach { symbol ->
            path(symbol, emptyList())?.let { return it.filter { entry -> entry.name.isNotBlank() } }
        }
        return emptyList()
    }

    private fun enclosingSymbolsFromFlat(
        symbols: List<SymbolInformation>,
        position: Position,
    ): List<LspEnclosingSymbol> {
        val matching = symbols.filter { info ->
            positionInRange(position, info.location?.range)
        }.sortedBy { rangeSpan(it.location?.range) }

        val best = matching.firstOrNull() ?: return emptyList()
        return buildList {
            best.containerName?.takeIf(String::isNotBlank)?.let {
                add(LspEnclosingSymbol(it, "Container"))
            }
            best.name?.takeIf(String::isNotBlank)?.let {
                add(LspEnclosingSymbol(it, best.kind?.name ?: "Symbol"))
            }
        }
    }

    private fun offsetToPosition(text: String, offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        var line = 0
        var character = 0
        for (index in 0 until safeOffset) {
            if (text[index] == '\n') {
                line++
                character = 0
            } else {
                character++
            }
        }
        return Position(line, character)
    }

    private fun positionInRange(position: Position, range: Range?): Boolean {
        range ?: return false
        val start = range.start ?: return false
        val end = range.end ?: return false
        if (position.line < start.line || position.line > end.line) return false
        if (position.line == start.line && position.character < start.character) return false
        if (position.line == end.line && position.character > end.character) return false
        return true
    }

    private fun rangeSpan(range: Range?): Int {
        range ?: return Int.MAX_VALUE
        val lineSpan = (range.end?.line ?: 0) - (range.start?.line ?: 0)
        val charSpan = (range.end?.character ?: 0) - (range.start?.character ?: 0)
        return lineSpan * 10_000 + charSpan
    }

    private fun Location.toSummary(): LspResolvedDefinitionSummary {
        val filePath = uriToLocalPath(uri)
        val startLine = range?.start?.line ?: 0
        return LspResolvedDefinitionSummary(
            name = null,
            filePath = filePath,
            startLine = startLine,
        )
    }

    private fun LocationLink.toSummary(): LspResolvedDefinitionSummary {
        val targetRange = targetSelectionRange ?: this.targetRange
        val filePath = uriToLocalPath(targetUri)
        val startLine = targetRange?.start?.line ?: 0
        return LspResolvedDefinitionSummary(
            name = null,
            filePath = filePath,
            startLine = startLine,
        )
    }

    private fun uriToLocalPath(uri: String?): String? = runCatching {
        uri ?: return null
        val parsed = URI(uri)
        if (parsed.scheme != "file") return null
        parsed.path
    }.getOrNull()

    private fun extractDefinitionName(headerLine: String): String? =
        DEFINITION_NAME.find(headerLine)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    private data class LspEnclosingSymbol(
        val name: String,
        val kind: String,
    )

    private data class LspResolvedDefinitionSummary(
        val name: String?,
        val filePath: String?,
        val startLine: Int,
        val snippet: String? = null,
    )

    private val IDENTIFIER_AT_END = Regex("""([A-Za-z_][A-Za-z0-9_]*)$""")
    private val DEFINITION_NAME = Regex("""(?:def|async\s+def|class|fun|function|interface|struct|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private const val DOCUMENT_SYMBOL_TIMEOUT_MS = 120
    private const val COMPLETION_TIMEOUT_MS = 120
    private const val DEFINITION_TIMEOUT_MS = 120
    private const val MAX_COMPLETION_ITEMS = 24
    private const val MAX_DEFINITION_SNIPPET_LINES = 6
    private const val MAX_DEFINITION_SNIPPET_CHARS = 400
}
