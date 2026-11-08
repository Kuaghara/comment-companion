package dev.commentcompanion.app

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.core.queue.QueueItem
import dev.commentcompanion.core.queue.WorkQueue
import dev.commentcompanion.ide.CompanionSettings
import dev.commentcompanion.ide.GoBuildVerifier
import dev.commentcompanion.ide.GoFileTracker
import dev.commentcompanion.ide.IdeFeedback
import dev.commentcompanion.ide.IdeWorkspace
import dev.commentcompanion.ide.NoopVerifier
import dev.commentcompanion.ide.RoundLedgerStore
import dev.commentcompanion.ports.VerifierPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 唯一编排器（project 级 light service）：扫描调度 + 串行 worker + 结果通知/重试。
 * 单轮细节在 [RoundExecutor]。
 */
@Service(Service.Level.PROJECT)
class CompanionController(private val project: Project) : Disposable {

    private val settings: CompanionSettings = service()
    private val ledger: RoundLedgerStore = project.service()
    private val workspace = IdeWorkspace(project)
    private val tracker = GoFileTracker(project)
    private val feedback = IdeFeedback(project)
    private val queue = WorkQueue()
    private val scheduler = AlarmScheduler(this)
    private val verifier: VerifierPort =
        if (settings.snapshot().verificationMode == VerificationMode.GO_BUILD) {
            GoBuildVerifier(project, settings)
        } else {
            NoopVerifier
        }
    private val executor = RoundExecutor(
        settings = settings,
        workspace = workspace,
        tracker = tracker,
        verifier = verifier,
        ledger = ledger,
        queue = queue,
        isPaused = { paused },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var paused: Boolean = false
        private set

    /** 已失败次数（file + instructionHash），实现「自动重试 1 次」的闸门。 */
    private val failures = ConcurrentHashMap<String, Int>()

    private val scanCycle = ScanCycle(
        tracker = tracker,
        workspace = workspace,
        queue = queue,
        settings = settings,
        shouldSkip = { file, hash -> shouldSkip(file, hash) },
    )

    fun start() {
        tracker.start()
        scheduleScan()
        scope.launch { workerLoop() }
    }

    fun pause() {
        paused = true
        queue.cancelAll()
    }

    fun resume() {
        paused = false
    }

    fun scanNow() {
        scanCycle.tick()
    }

    fun isPaused(): Boolean = paused

    fun queueSize(): Int = queue.pendingCount()

    private fun scheduleScan() {
        scheduler.schedule("scan-tick", settings.snapshot().scanIntervalMillis) {
            try {
                if (!paused) scanCycle.tick()
            } finally {
                if (scope.isActive) scheduleScan()
            }
        }
    }

    private fun shouldSkip(file: FileKey, hash: String): Boolean = (failures[failureKey(file, hash)] ?: 0) > 1

    private suspend fun workerLoop() {
        while (scope.isActive) {
            val item = queue.next()
            if (item == null) {
                delay(200)
                continue
            }
            val result = runCatching { executor.execute(item) }
                .getOrElse { RoundResult.Failed("内部错误：${it.message}") }
            when (result) {
                is RoundResult.Done -> {
                    feedback.notifyInfo("Comment Companion", "写完了：${result.functionName}")
                    feedback.showTransientHint(item.file, item.blockStartLine, "写完了 · 第 1 轮", 3_000)
                }
                is RoundResult.Failed -> {
                    feedback.notifyError("Comment Companion", "生成失败：${result.message}")
                    maybeRetry(item)
                }
                RoundResult.Stale, RoundResult.Cancelled -> Unit
            }
            queue.finish(item)
        }
    }

    /** 自动重试 1 次：仅第一次失败且策略允许时立刻重新入队。 */
    private fun maybeRetry(item: QueueItem) {
        val key = failureKey(item.file, item.instructionHash)
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        if (item.attempts == 0 &&
            count <= 1 &&
            settings.snapshot().failureRetry == FailureRetry.AUTO_ONCE
        ) {
            queue.enqueue(
                file = item.file,
                blockStartLine = item.blockStartLine,
                instructionHash = item.instructionHash,
                nowMillis = System.currentTimeMillis(),
                attempts = 1,
            )
        }
    }

    private fun failureKey(file: FileKey, hash: String): String = "${file.relativePath}|$hash"

    override fun dispose() {
        scope.cancel()
        scheduler.cancelAll()
        queue.cancelAll()
    }
}
