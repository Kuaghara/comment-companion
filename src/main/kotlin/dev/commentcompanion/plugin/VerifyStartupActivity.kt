package dev.commentcompanion.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.goide.psi.GoFile
import dev.commentcompanion.core.protocol.CommentProtocol
import java.awt.Graphics
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * M0 真机加载验证用的启动活动。
 *
 * 只有设置了系统属性 `commentcompanion.verifyTo` 时才动作（沙盒脚本会注入），
 * 日常使用时完全惰性。它做三件事，并把结果写成 JSON：
 * 1. 证明插件被 GoLand 成功加载；
 * 2. 证明项目打开后能拿到 Go 文件与 Go PSI（`GoFile`、`GoFunctionDeclaration`）；
 * 3. 证明注释协议解析在真实 PSI 文本上工作。
 */
class VerifyStartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        val outPath = System.getProperty("commentcompanion.verifyTo") ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = LinkedHashMap<String, String>()
            try {
                entries["plugin"] = quote("loaded")
                entries["project"] = quote(project.name)
                entries["projectPath"] = quote(project.basePath ?: "")

                val firstGo = AtomicReference<VirtualFile?>()
                ApplicationManager.getApplication().runReadAction {
                    ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
                        if (vf.extension == "go") {
                            firstGo.set(vf)
                            false
                        } else {
                            true
                        }
                    }
                }
                val goFile = firstGo.get()
                entries["goFile"] = quote(goFile?.path ?: "<none>")

                ApplicationManager.getApplication().runReadAction {
                    if (goFile == null) return@runReadAction
                    val psi = PsiManager.getInstance(project).findFile(goFile)
                    entries["psiClass"] = quote(psi?.javaClass?.name ?: "<null>")
                    if (psi == null) return@runReadAction
                    entries["textLength"] = psi.textLength.toString()

                    val directives = CommentProtocol.directives(psi.text.lines()).mapNotNull { it.directive?.text }
                    entries["textDirectives"] = directives.joinToString(",", "[", "]") { quote(it) }

                    if (psi is GoFile) {
                        val functions = psi.functions
                        entries["goFunctionNames"] = functions.mapNotNull { it.name }
                            .joinToString(",", "[", "]") { quote(it) }
                        entries["goFunctionsWithBody"] = functions.count { it.block != null }.toString()
                        entries["goMethods"] = psi.methods.size.toString()
                    } else {
                        entries["goFunctions"] = quote("not-a-GoFile")
                    }
                }

                if (goFile != null) verifyEditorAndInlay(project, goFile, entries)
            } catch (t: Throwable) {
                entries["error"] = quote(t.toString())
                entries["stack"] = quote(t.stackTraceToString().take(4000))
            } finally {
                try {
                    val json = entries.entries.joinToString(",\n  ") { (k, v) -> "${quote(k)}: $v" }
                    val target = File(outPath)
                    target.parentFile?.mkdirs()
                    target.writeText("{\n  $json\n}\n")
                } catch (_: Throwable) {
                    // 输出路径不可写时静默失败；沙盒验证通过日志也能看结果。
                }
            }
        }
    }

    /**
     * 在真机上验证编辑器与 Inlay：打开 Go 文件、取 TextEditor、在光标行尾加一个 Inlay 再销毁。
     * 任何一步失败都会写进报告，而不是让插件崩溃。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun verifyEditorAndInlay(project: Project, file: VirtualFile, entries: MutableMap<String, String>) {
        val added = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val editors = FileEditorManager.getInstance(project).openFile(file, true)
                val editor: Editor? = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
                entries["editor"] = quote(if (editor == null) "no-editor" else "opened")
                // 打开侧边窗口，验证面板能构造（异常会写进日志）
                runCatching {
                    com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                        .getToolWindow("Comment Companion")?.show()
                    entries["toolWindow"] = quote("shown")
                }.onFailure { entries["toolWindow"] = quote("error: ${it.message}") }
                if (editor != null) {
                    WriteCommandAction.runWriteCommandAction(project, "CC verify inlay", null, {
                        val inlay = editor.inlayModel.addAfterLineEndElement(
                            editor.caretModel.offset,
                            false,
                            object : EditorCustomElementRenderer {
                                override fun calcWidthInPixels(inlay: Inlay<*>): Int = 60
                                override fun paint(
                                    inlay: Inlay<*>,
                                    g: Graphics,
                                    targetRegion: Rectangle,
                                    textAttributes: TextAttributes,
                                ) = Unit
                            },
                        )
                        added.set(inlay != null)
                        inlay?.dispose()
                    })
                    entries["inlay"] = quote(if (added.get()) "added-and-disposed" else "null")
                }
            } catch (t: Throwable) {
                entries["inlayError"] = quote(t.toString())
            }
        }
    }

    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
