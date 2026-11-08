package dev.commentcompanion.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.ports.DiagnosticSignature
import dev.commentcompanion.ports.SettingsPort
import dev.commentcompanion.ports.VerificationBaseline
import dev.commentcompanion.ports.VerificationResult
import dev.commentcompanion.ports.VerifierPort
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 验证器：整模块 `go build ./...`。
 *
 * - baseline：本轮开始前的错误集合
 * - verify：重新构建后，只返回「新出现」的错误
 * - 设置里可切到 DAEMON_ONLY（此时 NoopVerifier 直接通过）
 */
class GoBuildVerifier(
    private val project: Project,
    private val settings: SettingsPort,
) : VerifierPort {

    private val log = Logger.getInstance(GoBuildVerifier::class.java)

    override fun baseline(file: FileKey): VerificationBaseline =
        VerificationBaseline(runBuild(file).toSet())

    override fun verify(file: FileKey, baseline: VerificationBaseline): VerificationResult {
        val snapshot = settings.snapshot()
        if (snapshot.verificationMode != VerificationMode.GO_BUILD) {
            return VerificationResult(ok = true, newErrors = emptyList())
        }
        val current = runBuild(file)
        val infrastructure = current.filter { it.file == "<build>" }
        if (infrastructure.isNotEmpty()) {
            log.warn("go build 基础设施错误: ${infrastructure.joinToString("; ") { it.message }}")
            return VerificationResult(
                ok = false,
                newErrors = infrastructure.map { it.message },
            )
        }
        val newErrors = current.filter { it !in baseline.errors }
        log.info("verify: current=${current.size} baseline=${baseline.errors.size} new=${newErrors.size}")
        return VerificationResult(
            ok = newErrors.isEmpty(),
            newErrors = newErrors.map { "${it.file}:${it.line}: ${it.message}" },
        )
    }

    private fun runBuild(file: FileKey): List<DiagnosticSignature> {
        val snapshot = settings.snapshot()
        if (snapshot.verificationMode != VerificationMode.GO_BUILD) return emptyList()
        val workingDir = moduleRoot(file) ?: project.basePath?.let { File(it) } ?: return emptyList()
        if (!workingDir.isDirectory) return emptyList()

        // go build 读磁盘：先把目标文档落盘，否则验证的是旧内容
        saveDocument(file, workingDir)

        return runCatching {
            val process = try {
                ProcessBuilder(snapshot.goExecutable, "build", "./...")
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: java.io.IOException) {
                log.warn("直接执行 ${snapshot.goExecutable} 失败，回退到 bash -lc: ${e.message}")
                ProcessBuilder("/bin/bash", "-lc", "${snapshot.goExecutable} build ./...")
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
            }
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(snapshot.buildTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching listOf(
                    DiagnosticSignature("<build>", 0, "go build 超时（${snapshot.buildTimeoutMillis}ms）"),
                )
            }
            val parsed = GoBuildOutput.parse(output)
            if (parsed.isEmpty() && process.exitValue() != 0) {
                listOf(DiagnosticSignature("<build>", 0, "go build 退出码 ${process.exitValue()}：${output.take(300)}"))
            } else {
                parsed
            }
        }.getOrElse { error ->
            listOf(DiagnosticSignature("<build>", 0, "go build 失败：${error.message}"))
        }
    }

    private fun saveDocument(file: FileKey, workingDir: File) {
        try {
            ApplicationManager.getApplication().invokeAndWait {
                FileDocumentManager.getInstance().saveAllDocuments()
            }
            log.info("验证前已保存文档（saveAllDocuments）")
        } catch (t: Throwable) {
            log.warn("验证前保存文档失败: ${t.message}", t)
        }
    }

    /** 从文件向上找最近的 go.mod。 */
    private fun moduleRoot(file: FileKey): File? {
        val base = project.basePath ?: return null
        var dir: File? = File(base, file.relativePath).parentFile
        while (dir != null) {
            if (File(dir, "go.mod").isFile) return dir
            if (dir.path == base) return null
            dir = dir.parentFile
        }
        return null
    }
}

/** DAEMON_ONLY 模式：不做进程构建，直接放行（M2 起由 GoLand daemon 诊断兜底，后续完善）。 */
object NoopVerifier : VerifierPort {
    override fun baseline(file: FileKey): VerificationBaseline = VerificationBaseline(emptySet())

    override fun verify(file: FileKey, baseline: VerificationBaseline): VerificationResult =
        VerificationResult(ok = true, newErrors = emptyList())
}
