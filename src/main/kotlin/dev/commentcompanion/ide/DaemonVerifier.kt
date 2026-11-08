package dev.commentcompanion.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.Processor
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.ports.DiagnosticSignature
import dev.commentcompanion.ports.VerificationBaseline
import dev.commentcompanion.ports.VerificationResult
import dev.commentcompanion.ports.VerifierPort

/**
 * DAEMON_ONLY 验证：读 GoLand 的 daemon 诊断（不跑 go build 进程）。
 *
 * 写入后触发一次高亮、等待分析完成（最多 5 秒），收集 ERROR 级诊断，
 * 与 baseline 求差集；分析超时则放行（避免阻塞用户流程）。
 */
class DaemonVerifier(private val project: Project) : VerifierPort {

    override fun baseline(file: FileKey): VerificationBaseline =
        VerificationBaseline(collect(file).toSet())

    override fun verify(file: FileKey, baseline: VerificationBaseline): VerificationResult {
        val current = collect(file)
        val newErrors = current.filter { it !in baseline.errors }
        return VerificationResult(
            ok = newErrors.isEmpty(),
            newErrors = newErrors.map { "${it.file}:${it.line}: ${it.message}" },
        )
    }

    private fun collect(file: FileKey): List<DiagnosticSignature> {
        val base = project.basePath ?: return emptyList()
        val path = if (file.relativePath.startsWith("/")) file.relativePath else "$base/${file.relativePath}"
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return emptyList()
        val psi = ApplicationManager.getApplication()
            .runReadAction(Computable { PsiManager.getInstance(project).findFile(vf) }) ?: return emptyList()
        val document = ApplicationManager.getApplication()
            .runReadAction(Computable { PsiDocumentManager.getInstance(project).getDocument(psi) })
            ?: return emptyList()

        ApplicationManager.getApplication().invokeAndWait {
            DaemonCodeAnalyzer.getInstance(project).restart(psi)
        }
        val analyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val finished = ApplicationManager.getApplication().runReadAction(Computable {
                analyzer.isErrorAnalyzingFinished(psi)
            })
            if (finished) break
            Thread.sleep(50)
        }

        val fileLabel = file.relativePath
        val result = ArrayList<DiagnosticSignature>()
        ApplicationManager.getApplication().runReadAction(Computable<Unit> {
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                HighlightSeverity.ERROR,
                0,
                document.textLength,
                Processor { info: HighlightInfo ->
                    val line = document.getLineNumber(info.startOffset.coerceIn(0, document.textLength)) + 1
                    result += DiagnosticSignature(fileLabel, line, info.description)
                    true
                },
            )
        })
        return result
    }
}
