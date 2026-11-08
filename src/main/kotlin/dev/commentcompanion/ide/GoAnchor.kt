package dev.commentcompanion.ide

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoMethodDeclaration
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.FunctionSnapshot
import dev.commentcompanion.core.model.Hashing
import dev.commentcompanion.core.protocol.CommentBlock
import dev.commentcompanion.core.protocol.CommentProtocol

/**
 * 注释 → 上方函数的 PSI 锚定。
 *
 * 规则（与设计文档一致）：
 * - 指令注释块必须直接位于函数上方；
 * - 注释块与函数声明之间只允许 0 或 1 个空行；
 * - 函数必须有 body（声明/接口方法不处理）。
 */
object GoAnchor {

    fun snapshot(goFile: GoFile, projectPath: String?, blockStartLine: Int): FunctionSnapshot? {
        val document = PsiDocumentManager.getInstance(goFile.project).getDocument(goFile) ?: return null
        if (blockStartLine !in 0 until document.lineCount) return null

        val lineStart = document.getLineStartOffset(blockStartLine)
        val comments = PsiTreeUtil.findChildrenOfType(goFile, PsiComment::class.java)
        val anchorComment = comments.firstOrNull { it.textRange.containsOffset(lineStart) } ?: return null

        // 收集同一注释块（连续注释，注释之间最多 1 个空行）。
        val blockComments = collectBlock(anchorComment)
        val first = blockComments.first()
        val last = blockComments.last()

        // 跳过注释块与函数之间的空白；只允许 0 或 1 个空行。
        val function = findFunctionAfter(last) ?: return null
        val body = function.block ?: return null

        val commentStart = first.textRange.startOffset
        val commentEnd = last.textRange.endOffset
        val commentRawLines = textLines(document.text, commentStart, commentEnd)
        val parsed = CommentProtocol.scan(commentRawLines).firstOrNull { it.directive != null } ?: return null
        val relativeDirective = parsed.directive ?: return null
        // CommentProtocol 以块为单位解析，markerLine 是块内相对行号；转成文件绝对行号
        val blockFirstLine = document.getLineNumber(commentStart)
        val directive = relativeDirective.copy(markerLine = blockFirstLine + relativeDirective.markerLine)

        val signatureText = function.signature?.text ?: return null
        val receiverType = (function as? GoMethodDeclaration)?.receiverType?.text
        val relativePath = relativePath(goFile, projectPath)

        val key = FunctionKey(
            languageId = "go",
            file = FileKey(relativePath),
            name = function.name ?: return null,
            receiverType = receiverType,
            signatureHash = Hashing.sha256(normalizeWhitespace(signatureText)),
        )

        val bodyText = body.text
        val descriptionLines = commentRawLines
            .take(relativeDirective.markerLine.coerceAtLeast(0))
            .map { it.trimStart().removePrefix("//").removePrefix(" ").trimEnd() }

        return FunctionSnapshot(
            key = key,
            signatureText = signatureText,
            bodyRange = body.textRange.startOffset..body.textRange.endOffset,
            bodyText = bodyText,
            commentBlockRange = commentStart..commentEnd,
            commentRawLines = commentRawLines,
            descriptionLines = descriptionLines,
            instruction = directive,
            documentStamp = document.modificationStamp,
            bodyHash = Hashing.sha256(bodyText),
            fileTextHash = Hashing.sha256(document.text),
        )
    }

    private fun collectBlock(comment: PsiComment): List<PsiComment> {
        val result = mutableListOf(comment)
        var current: PsiElement? = comment
        // 向上合并连续注释
        while (true) {
            val prev = previousComment(current!!) ?: break
            result.add(0, prev)
            current = prev
        }
        current = comment
        while (true) {
            val next = nextComment(current!!) ?: break
            result.add(next)
            current = next
        }
        return result
    }

    private fun previousComment(element: PsiElement): PsiComment? {
        var sibling: PsiElement? = element.prevSibling
        var newlines = 0
        while (sibling != null) {
            when {
                sibling is PsiComment -> return sibling
                sibling is PsiWhiteSpace -> {
                    newlines += sibling.text.count { it == '\n' }
                    if (newlines > 1) return null
                }
                else -> return null
            }
            sibling = sibling.prevSibling
        }
        return null
    }

    private fun nextComment(element: PsiElement): PsiComment? {
        var sibling: PsiElement? = element.nextSibling
        var newlines = 0
        while (sibling != null) {
            when {
                sibling is PsiComment -> return sibling
                sibling is PsiWhiteSpace -> {
                    newlines += sibling.text.count { it == '\n' }
                    if (newlines > 1) return null
                }
                else -> return null
            }
            sibling = sibling.nextSibling
        }
        return null
    }

    private fun findFunctionAfter(comment: PsiComment): GoFunctionOrMethodDeclaration? {
        var sibling: PsiElement? = comment.nextSibling
        var newlines = 0
        while (sibling != null) {
            when {
                sibling is PsiWhiteSpace -> {
                    newlines += sibling.text.count { it == '\n' }
                    if (newlines > 2) return null // 超过 1 个空行
                }
                sibling is GoFunctionOrMethodDeclaration -> return sibling
                else -> return null
            }
            sibling = sibling.nextSibling
        }
        return null
    }

    private fun textLines(text: String, start: Int, end: Int): List<String> =
        text.substring(start, end).lines()

    private fun relativePath(goFile: GoFile, projectPath: String?): String {
        val path = goFile.virtualFile?.path ?: return goFile.name
        return if (projectPath != null && path.startsWith("$projectPath/")) {
            path.removePrefix("$projectPath/")
        } else {
            goFile.name
        }
    }

    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
