package dev.commentcompanion.app

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.RoundStage
import dev.commentcompanion.core.model.EditOp
import dev.commentcompanion.core.model.EditPlan
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.core.queue.QueueItem
import dev.commentcompanion.core.queue.WorkQueue
import dev.commentcompanion.ide.CompanionSettings
import dev.commentcompanion.ide.DaemonVerifier
import dev.commentcompanion.ide.GoBuildVerifier
import dev.commentcompanion.ide.GoFileTracker
import dev.commentcompanion.ide.IdeFeedback
import dev.commentcompanion.ide.IdeWorkspace
import dev.commentcompanion.ide.NoopVerifier
import dev.commentcompanion.ide.RoundJournalStore
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
    private val journal: RoundJournalStore = project.service()
    private val workspace = IdeWorkspace(project)
    private val tracker = GoFileTracker(project)
    private val feedback = IdeFeedback(project)
    private val queue = WorkQueue()
    private val scheduler = AlarmScheduler(this)
    private val verifier: VerifierPort =
        when (settings.snapshot().verificationMode) {
            VerificationMode.GO_BUILD -> GoBuildVerifier(project, settings)
            VerificationMode.DAEMON_ONLY -> DaemonVerifier(project)
        }
    private val executor = RoundExecutor(
        settings = settings,
        workspace = workspace,
        tracker = tracker,
        verifier = verifier,
        ledger = ledger,
        journal = journal,
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
        recoverJournal()
        scheduleScan()
        scope.launch { workerLoop() }
    }

    /** 崩溃恢复：上次 body 已落笔但没走完 → 回滚到原 body。 */
    private fun recoverJournal() {
        val entry = journal.pending() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                if (entry.phase != "APPLIED") {
                    journal.clear()
                    return@launch
                }
                val snapshot = workspace.snapshot(FileKey(entry.file), entry.blockStartLine)
                if (snapshot == null) {
                    feedback.notifyError("Comment Companion", "检测到未完成修改，但定位失败；请用 Local History 恢复 ${entry.file}")
                    journal.clear()
                    return@launch
                }
                val plan = EditPlan(
                    target = snapshot.key,
                    snapshot = snapshot,
                    ops = listOf(EditOp.ReplaceBody(entry.originalBody)),
                    commandName = "Comment Companion: 崩溃恢复",
                )
                when (workspace.commit(plan)) {
                    is dev.commentcompanion.ports.CommitOutcome.Applied -> {
                        feedback.notifyInfo("Comment Companion", "已恢复上次中断的修改：${snapshot.key.name}")
                        journal.clear()
                    }
                    else -> {
                        feedback.notifyError("Comment Companion", "自动恢复失败，请用 Local History 检查 ${entry.file}")
                        journal.clear()
                    }
                }
            } catch (_: Throwable) {
                journal.clear()
            }
        }
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

    fun settings(): CompanionSettings = settings

    /** 从面板跳转到文件/行。 */
    fun openFile(relativePath: String, line: Int) {
        val vf = workspace.findVirtualFile(FileKey(relativePath)) ?: return
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true)
            if (line > 0) {
                val offset = runCatching {
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(vf)?.getLineStartOffset((line - 1).coerceAtLeast(0))
                }.getOrNull() ?: 0
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, offset).navigate(true)
            }
        }
    }

    fun queueSize(): Int = queue.pendingCount()

    /** 面板只读快照（在 EDT 调用；内部结构已加锁）。 */
    fun panelSnapshot(): PanelSnapshot {
        val snapshot = settings.snapshot()
        val active = queue.active()
        val queued = queue.pendingItems()
        val recent = ledger.recent(20)
        val failedPending = recent
            .filter { it.stage == RoundStage.FAILED }
            .distinctBy { it.key.ledgerKey() + it.instructionHash }
            .map { record ->
                PendingRow(
                    title = "${record.key.file.relativePath} › ${record.key.name}",
                    detail = record.error ?: "生成失败，可重试",
                    file = record.key.file.relativePath,
                )
            }
        val questionPending = ledger.pendingQuestions().map { state ->
            PendingRow(
                title = "${state.key.file.relativePath} › ${state.key.name}",
                detail = "ai？ ${state.question ?: "等你回答"}",
                file = state.key.file.relativePath,
                line = state.questionLine,
            )
        }
        val pending = questionPending + failedPending

        val statusText = when {
            paused -> "已暂停"
            active != null -> "正在写 ${active.file.relativePath} › ${functionName(active.file.relativePath)}"
            queued.isNotEmpty() -> "等待处理"
            else -> "空闲"
        }
        val secondaryText = buildString {
            append("队列 ")
            append(queue.pendingCount())
            active?.let {
                append(" · 当前：")
                append(it.file.relativePath)
                append("（")
                append(it.stage.name.lowercase())
                append("）")
            }
        }
        return PanelSnapshot(
            statusText = statusText,
            secondaryText = secondaryText,
            running = active != null,
            paused = paused,
            pending = pending,
            active = active?.let {
                ActiveRow(
                    file = it.file.relativePath,
                    function = functionName(it.file.relativePath),
                    stage = it.stage,
                    attempt = it.attempts + 1,
                )
            },
            queue = queued.map {
                QueueRow(
                    file = it.file.relativePath,
                    function = functionName(it.file.relativePath),
                    instructionHash = it.instructionHash.take(8),
                    waitSeconds = ((System.currentTimeMillis() - it.enqueuedAt) / 1000).coerceAtLeast(0),
                )
            },
            recent = recent.map {
                RecentRow(
                    file = it.key.file.relativePath,
                    function = it.key.name,
                    stage = it.stage,
                    attempts = it.attempts,
                    error = it.error,
                )
            },
            settings = snapshot,
        )
    }

    private fun functionName(relativePath: String): String = relativePath.substringAfterLast('/')

    private fun scheduleScan() {
        scheduler.schedule("scan-tick", settings.snapshot().scanIntervalMillis) {
            try {
                if (!paused) scanCycle.tick()
            } finally {
                if (scope.isActive) scheduleScan()
            }
        }
    }

    private fun shouldSkip(file: FileKey, hash: String): Boolean =
        (failures[failureKey(file, hash)] ?: 0) > 1 ||
            ledger.pendingQuestionFor(file, hash) != null

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
                is RoundResult.Question -> {
                    feedback.notifyInfo("Comment Companion", "需要你的回答：${result.text}")
                    feedback.showTransientHint(item.file, item.blockStartLine, "等你回答 · ai？", 4_000)
                }
                RoundResult.Stale -> requeueStale(item)
                RoundResult.Cancelled -> Unit
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

    private fun requeueStale(item: QueueItem) {
        if (item.attempts >= 1) return
        val text = workspace.readText(item.file) ?: return
        val block = dev.commentcompanion.core.protocol.CommentProtocol.directives(text.lines()).firstOrNull {
            it.directive != null && dev.commentcompanion.core.model.Hashing.sha256(it.directive!!.text) == item.instructionHash
        } ?: return
        queue.enqueue(item.file, block.startLine, item.instructionHash, System.currentTimeMillis(), item.attempts + 1)
    }

    private fun failureKey(file: FileKey, hash: String): String = "${file.relativePath}|$hash"

    override fun dispose() {
        scope.cancel()
        scheduler.cancelAll()
        queue.cancelAll()
    }
}
