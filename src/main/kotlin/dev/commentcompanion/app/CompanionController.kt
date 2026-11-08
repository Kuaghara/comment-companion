package dev.commentcompanion.app

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.EditOp
import dev.commentcompanion.core.model.EditPlan
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.Hashing
import dev.commentcompanion.core.model.RoundRecord
import dev.commentcompanion.core.model.RoundStage
import dev.commentcompanion.core.model.FunctionSnapshot
import dev.commentcompanion.core.prompt.EngineRequest
import dev.commentcompanion.core.prompt.EngineResult
import dev.commentcompanion.core.prompt.PromptPayload
import dev.commentcompanion.core.queue.QueueItem
import dev.commentcompanion.core.queue.WorkQueue
import dev.commentcompanion.engine.FakeEngine
import dev.commentcompanion.ide.CompanionSettings
import dev.commentcompanion.ide.GoFileTracker
import dev.commentcompanion.ide.IdeFeedback
import dev.commentcompanion.ide.IdeWorkspace
import dev.commentcompanion.ide.RoundLedgerStore
import dev.commentcompanion.ports.CommitOutcome
import dev.commentcompanion.ports.EnginePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 唯一编排器（project 级 light service）。
 *
 * M1b 链路：空闲扫描 → 入队 → 串行 worker → 快照/锚定 → FakeEngine 生成
 * → 落笔（body+注释）→ 账本 → 反馈。
 * 真实引擎、go build 验证、面板在 M2/M3 接入。
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
    private val engine: EnginePort = FakeEngine()
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
                if (!scope.isActive) return@schedule
                scheduleScan()
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
            runCatching { process(item) }
                .onFailure { fail(item, "内部错误：${it.message}") }
            queue.finish(item)
        }
    }

    private suspend fun process(item: QueueItem) {
        if (paused) {
            queue.markStage(item.seq, RoundStage.CANCELLED)
            return
        }
        queue.markStage(item.seq, RoundStage.SNAPSHOT)
        val snapshot: FunctionSnapshot = workspace.snapshot(item.file, item.blockStartLine) ?: run {
            queue.markStage(item.seq, RoundStage.STALE)
            return
        }

        val payload = PromptPayload(
            languageId = snapshot.key.languageId,
            file = snapshot.key.file.relativePath,
            functionName = snapshot.key.name,
            signature = snapshot.signatureText,
            descriptionLines = snapshot.descriptionLines,
            existingBody = snapshot.bodyText,
            instruction = snapshot.instruction.text,
        )
        queue.markStage(item.seq, RoundStage.GENERATING)
        val config = settings.snapshot().engine
        val result = engine.generate(
            EngineRequest(
                payload = payload,
                model = config.model,
                temperature = config.temperature,
                timeoutMillis = config.timeoutMillis,
            ),
        )

        when (result) {
            is EngineResult.Failure -> fail(item, "${result.kind}: ${result.message}")
            is EngineResult.Success -> {
                queue.markStage(item.seq, RoundStage.PARSING)
                val output = result.output
                if (output.body.isBlank() || output.comment.isEmpty()) {
                    fail(item, "模型输出不完整（body/comment 缺失）")
                    return
                }
                val preserved = snapshot.descriptionLines.map { it.trim() }.filter { it.isNotEmpty() }
                val modelComment = output.comment.map { it.trim().removePrefix("//").trim() }
                val commentLines = preserved + modelComment
                if (commentLines.any { dev.commentcompanion.core.protocol.CommentProtocol.classify(it) != null }) {
                    fail(item, "模型输出的注释仍包含 ai 标记，拒绝落笔")
                    return
                }
                val plan = EditPlan(
                    target = snapshot.key,
                    snapshot = snapshot,
                    ops = listOf(
                        EditOp.ReplaceBody(output.body),
                        EditOp.ReplaceComment(commentLines),
                    ),
                    commandName = "Comment Companion: ${snapshot.key.name}",
                )
                queue.markStage(item.seq, RoundStage.APPLYING)
                when (val outcome = workspace.commit(plan)) {
                    is CommitOutcome.Applied -> {
                        workspace.findVirtualFile(item.file)?.let { tracker.markOwnWrite(it, outcome.newStamp) }
                        queue.markStage(item.seq, RoundStage.NORMALIZING)
                        record(item, snapshot, RoundStage.DONE)
                        feedback.notifyInfo("Comment Companion", "写完了：${snapshot.key.name}")
                        feedback.showTransientHint(item.file, item.blockStartLine, "写完了 · 第 1 轮", 3_000)
                        queue.markStage(item.seq, RoundStage.DONE)
                        VerifyHooks.roundCompleted(snapshot.key.name, "DONE")
                    }
                    is CommitOutcome.Stale -> {
                        queue.markStage(item.seq, RoundStage.STALE)
                        VerifyHooks.roundCompleted(snapshot.key.name, "STALE")
                        feedback.notifyInfo("Comment Companion", "文档已变化，本轮已放弃")
                    }
                    is CommitOutcome.Failed -> fail(item, outcome.reason)
                }
            }
        }
    }

    private fun fail(item: QueueItem, message: String) {
        queue.markStage(item.seq, RoundStage.FAILED)
        val key = failureKey(item.file, item.instructionHash)
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        feedback.notifyError("Comment Companion", "生成失败：$message")
        VerifyHooks.roundCompleted(item.file.relativePath, "FAILED", message)
        // 自动重试 1 次：attempts 为 0 且策略允许时立刻重新入队
        if (item.attempts == 0 && settings.snapshot().failureRetry == FailureRetry.AUTO_ONCE) {
            queue.enqueue(
                file = item.file,
                blockStartLine = item.blockStartLine,
                instructionHash = item.instructionHash,
                nowMillis = System.currentTimeMillis(),
                attempts = 1,
            )
        }
    }

    private fun record(item: QueueItem, snapshot: FunctionSnapshot, stage: RoundStage) {
        val previous = ledger.lastRecord(snapshot.key)
        val record = RoundRecord(
            key = snapshot.key,
            roundNo = (previous?.roundNo ?: 0) + 1,
            instructionHash = Hashing.sha256(snapshot.instruction.text),
            stage = stage,
            startedAt = item.enqueuedAt,
            finishedAt = System.currentTimeMillis(),
            model = settings.snapshot().engine.model,
        )
        ledger.record(record)
    }

    private fun failureKey(file: FileKey, hash: String): String = "${file.relativePath}|$hash"

    override fun dispose() {
        scope.cancel()
        scheduler.cancelAll()
        queue.cancelAll()
    }
}
