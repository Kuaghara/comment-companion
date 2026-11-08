package dev.commentcompanion.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.util.PsiTreeUtil
import com.goide.psi.GoFile
import dev.commentcompanion.core.model.EditOp
import dev.commentcompanion.core.model.EditPlan
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.FunctionSnapshot
import dev.commentcompanion.ports.CommitOutcome
import dev.commentcompanion.ports.DocumentSnapshot
import dev.commentcompanion.ports.PreviewResult
import dev.commentcompanion.ports.WorkspacePort

/**
 * WorkspacePort 的 GoLand 实现：所有 PSI / 文档操作都收敛在这里。
 */
class IdeWorkspace(private val project: Project) : WorkspacePort {

    override fun openDocuments(): List<FileKey> {
        val manager = FileEditorManager.getInstance(project)
        return manager.openFiles
            .filter { it.extension == "go" && it.isValid }
            .mapNotNull { toFileKey(it) }
    }

    override fun documentStamp(file: FileKey): Long? {
        val vf = findVirtualFile(file) ?: return null
        return FileDocumentManager.getInstance().getDocument(vf)?.modificationStamp
    }

    override fun readText(file: FileKey): String? = runRead {
        findVirtualFile(file)?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
    }

    override fun snapshot(file: FileKey, blockStartLine: Int): FunctionSnapshot? {
        val vf = findVirtualFile(file) ?: return null
        syncPsi(vf)
        return runRead {
            val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: return@runRead null
            GoAnchor.snapshot(psi, project.basePath, blockStartLine)
        }
    }

    /** 把 Document 的改动提交给 PSI（否则锚定用的是旧语法树，offset 会错位）。 */
    private fun syncPsi(vf: VirtualFile) {
        val psi = runRead { PsiManager.getInstance(project).findFile(vf) } ?: return
        val document = runRead { PsiDocumentManager.getInstance(project).getDocument(psi) } ?: return
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    override fun commit(plan: EditPlan): CommitOutcome {
        var outcome: CommitOutcome = CommitOutcome.Failed("unknown")
        ApplicationManager.getApplication().invokeAndWait {
            val vf = findVirtualFile(plan.snapshot.key.file)
            val psi = vf?.let { PsiManager.getInstance(project).findFile(it) }
            val document = psi?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            if (vf == null || psi == null || document == null) {
                outcome = CommitOutcome.Failed("找不到文件或文档")
                return@invokeAndWait
            }
            if (document.modificationStamp != plan.snapshot.documentStamp) {
                outcome = CommitOutcome.Stale(document.modificationStamp)
                return@invokeAndWait
            }
            WriteCommandAction.runWriteCommandAction(project, plan.commandName, null, {
                // body 的 offset 在注释之后：先写 body，注释区 offset 不受影响
                plan.ops.filterIsInstance<EditOp.ReplaceBody>().lastOrNull()?.let { op ->
                    document.replaceString(
                        plan.snapshot.bodyRange.first,
                        plan.snapshot.bodyRange.last,
                        op.newText,
                    )
                }
                plan.ops.filterIsInstance<EditOp.ReplaceComment>().lastOrNull()?.let { op ->
                    val rendered = op.newLines.joinToString("\n") { line ->
                        val trimmed = line.trimEnd()
                        if (trimmed.trimStart().startsWith("//")) trimmed.trimStart() else if (trimmed.isEmpty()) "//" else "// $trimmed"
                    }
                    document.replaceString(
                        plan.snapshot.commentBlockRange.first,
                        plan.snapshot.commentBlockRange.last,
                        rendered,
                    )
                }
                // 追加注释行（写 ai？）：插在注释块末尾，不影响已有区间
                plan.ops.filterIsInstance<EditOp.AppendCommentLine>().lastOrNull()?.let { op ->
                    document.insertString(plan.snapshot.commentBlockRange.last, "\n// ${op.text.trimEnd()}")
                }
                // 函数体之后追加独立注释块（ai？），与指令注释块完全隔离
                plan.ops.filterIsInstance<EditOp.AppendTrailingComment>().lastOrNull()?.let { op ->
                    document.insertString(plan.snapshot.bodyRange.last, "\n\n// ${op.text.trimEnd()}")
                }
                // imports：M2 实现（GoFile.addImport）
            }, psi)
            outcome = CommitOutcome.Applied(document.modificationStamp)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        return outcome
    }

    override fun previewParse(file: FileKey, candidateText: String): PreviewResult = runRead {
        val vf = findVirtualFile(file) ?: return@runRead PreviewResult(false, listOf("文件不存在"))
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return@runRead PreviewResult(false, listOf("PSI 不可用"))
        val factory = PsiFileFactory.getInstance(project)
        val preview = factory.createFileFromText(vf.name, psi.language, candidateText)
        val errors = PsiTreeUtil.findChildrenOfType(preview, PsiErrorElement::class.java)
            .map { "line ${preview.text.substring(0, it.textRange.startOffset).count { c -> c == '\n' } + 1}: ${it.errorDescription}" }
            .take(5)
        PreviewResult(errors.isNotEmpty(), errors)
    }

    override fun cleanupTrailingQuestion(file: FileKey, key: FunctionKey): Boolean {
        val vf = findVirtualFile(file) ?: return false
        var removed = false
        ApplicationManager.getApplication().invokeAndWait {
            runCatching {
                val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: return@invokeAndWait
                val target = psi.functions.firstOrNull { it.name == key.name }
                    ?: psi.methods.firstOrNull { it.name == key.name }
                    ?: return@invokeAndWait
                var sibling = target.nextSibling
                while (sibling is PsiWhiteSpace) sibling = sibling.nextSibling
                if (sibling is PsiComment &&
                    sibling.text.trimStart().removePrefix("//").trimStart().startsWith("ai？")
                ) {
                    WriteCommandAction.runWriteCommandAction(project, "Comment Companion: 清理 ai？", null, {
                        sibling.delete()
                    })
                    removed = true
                }
            }
        }
        return removed
    }

    fun toFileKey(file: VirtualFile): FileKey? {
        val base = project.basePath ?: return null
        val path = file.path
        val relative = if (path.startsWith("$base/")) path.removePrefix("$base/") else file.name
        return FileKey(relative)
    }

    fun findVirtualFile(file: FileKey): VirtualFile? {
        val base = project.basePath ?: return null
        val path = if (file.relativePath.startsWith("/")) file.relativePath else "$base/${file.relativePath}"
        return LocalFileSystem.getInstance().findFileByPath(path)
    }

    private fun <T> runRead(action: () -> T): T =
        ApplicationManager.getApplication().runReadAction(Computable<T> { action() })
}
