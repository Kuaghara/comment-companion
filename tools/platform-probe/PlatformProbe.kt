package dev.commentcompanion.tools

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.goide.psi.GoBlock
import com.goide.psi.GoFile
import com.goide.psi.GoFunctionDeclaration
import java.awt.Graphics
import java.awt.Rectangle

/**
 * 平台兼容性探针：只用于 `scripts/check-platform.sh` 的编译检查，不打包进插件。
 *
 * 目的：验证 Kotlin 源码可以直接使用 GoLand 2026.2 的平台 API 与 Go PSI，
 * 包括 Inlay、Document 写入、Go 函数枚举/函数体定位。
 */
@Suppress("unused", "UNUSED_PARAMETER")
object PlatformProbe {

    fun inlay(editor: Editor) {
        val model: InlayModel = editor.inlayModel
        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = 80
            override fun paint(
                inlay: Inlay<*>,
                g: Graphics,
                targetRegion: Rectangle,
                textAttributes: TextAttributes,
            ) = Unit
        }
        model.addAfterLineEndElement(editor.caretModel.offset, false, renderer)
    }

    fun scan(file: PsiFile): Int {
        if (file !is GoFile) return -1
        var count = 0
        for (fn in file.functions) count += describe(fn)
        return count
    }

    private fun describe(fn: GoFunctionDeclaration): Int {
        val name: String? = fn.name
        val signature = fn.signature
        val params = signature?.parameters?.text
        val result = signature?.result?.text
        val block: GoBlock? = fn.block
        val bodyStart = block?.textRange?.startOffset ?: -1
        val bodyEnd = block?.textRange?.endOffset ?: -1
        return name?.length ?: -1
    }

    fun write(project: Project, file: PsiFile, offset: Int, text: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Comment Companion probe", null, {
            document.insertString(offset.coerceIn(0, document.textLength), text)
        }, file)
    }
}
