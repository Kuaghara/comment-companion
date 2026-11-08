package dev.commentcompanion.app

import dev.commentcompanion.core.model.EditOp
import dev.commentcompanion.core.model.EditPlan
import dev.commentcompanion.core.model.FunctionSnapshot
import dev.commentcompanion.core.model.Hashing
import dev.commentcompanion.core.model.QuestionResolver
import dev.commentcompanion.core.model.QuestionState
import dev.commentcompanion.core.model.QuestionStatus
import dev.commentcompanion.core.model.RepairPolicy
import dev.commentcompanion.core.model.RoundRecord
import dev.commentcompanion.core.model.RoundStage
import dev.commentcompanion.core.prompt.EngineRequest
import dev.commentcompanion.core.prompt.EngineResult
import dev.commentcompanion.core.prompt.PromptPayload
import dev.commentcompanion.core.protocol.CommentProtocol
import dev.commentcompanion.core.protocol.MarkerType
import dev.commentcompanion.core.queue.QueueItem
import dev.commentcompanion.core.queue.WorkQueue
import dev.commentcompanion.engine.EngineFactory
import dev.commentcompanion.ide.CompanionSettings
import dev.commentcompanion.ide.GoFileTracker
import dev.commentcompanion.ide.IdeWorkspace
import dev.commentcompanion.ide.RoundJournalStore
import dev.commentcompanion.ide.RoundLedgerStore
import dev.commentcompanion.ports.CommitOutcome
import dev.commentcompanion.ports.VerifierPort

sealed interface RoundResult {
    data class Done(val functionName: String) : RoundResult
    data class Failed(val message: String) : RoundResult
    data object Stale : RoundResult
    data object Cancelled : RoundResult
    data class Question(val text: String) : RoundResult
}

/**
 * 单轮执行器：
 *
 * SNAPSHOT → [baseline] → 循环{GENERATING → PARSING → APPLYING(body) → VERIFYING
 *                              ├ ok → NORMALIZING(comment) → DONE
 *                              └ 新错误 → 回滚 body → REPAIRING（≤5 次/60s）
 *                          } → FAILED（回滚后）
 *
 * - 每次落笔都用「当前最新快照」构造计划，避免 precondition 过期
 * - 失败/取消时用 original.bodyText 回滚 body，`ai：` 注释保留
 * - 注释规范化是收尾阶段（第二次落笔，仅成功时发生）
 */
class RoundExecutor(
    private val settings: CompanionSettings,
    private val workspace: IdeWorkspace,
    private val tracker: GoFileTracker,
    private val verifier: VerifierPort,
    private val ledger: RoundLedgerStore,
    private val journal: RoundJournalStore,
    private val queue: WorkQueue,
    private val isPaused: () -> Boolean,
) {

    suspend fun execute(item: QueueItem): RoundResult {
        if (isPaused()) return cancelled(item)
        queue.markStage(item.seq, RoundStage.SNAPSHOT)
        val original = snapshotFor(item) ?: return stale(item)
        resolveExistingQuestion(original)
        val pendingQuestion = ledger.question(original.key)
        if (pendingQuestion?.status == QuestionStatus.PENDING &&
            pendingQuestion.instructionHash == Hashing.sha256(original.instruction.text)
        ) {
            queue.markStage(item.seq, RoundStage.WAITING_ANSWER)
            return RoundResult.Question("等待用户回答问题")
        }

        val baseline = verifier.baseline(item.file)
        val policy = RepairPolicy()
        val startedAt = System.currentTimeMillis()
        var repairErrors: List<String> = emptyList()
        var attempt = 0
        var lastErrors: List<String> = emptyList()
        var bodyApplied = false

        while (true) {
            if (isPaused()) return cancelled(item, rollback = false)
            attempt++
            if (attempt > policy.maxAttempts) {
                return failed(item, original, "修复轮数超限（${policy.maxAttempts}）")
            }
            if (System.currentTimeMillis() - startedAt > policy.maxDurationMillis) {
                return failed(item, original, "修复超时（${policy.maxDurationMillis}ms）")
            }

            queue.markStage(item.seq, RoundStage.GENERATING)
            val snapshot = settings.snapshot()
            val payload = PromptPayload(
                languageId = original.key.languageId,
                file = original.key.file.relativePath,
                functionName = original.key.name,
                signature = original.signatureText,
                descriptionLines = original.descriptionLines,
                existingBody = original.bodyText,
                instruction = original.instruction.text,
                repairErrors = repairErrors,
            )
            val result = EngineFactory.create(settings).generate(
                EngineRequest(
                    payload = payload,
                    model = snapshot.engine.model,
                    temperature = snapshot.engine.temperature,
                    timeoutMillis = snapshot.engine.timeoutMillis,
                ),
            )

            when (result) {
                is EngineResult.Failure -> return failed(item, original, "${result.kind}: ${result.message}")
                is EngineResult.Success -> {
                    queue.markStage(item.seq, RoundStage.PARSING)
                    val output = result.output
                    if (output.question != null) {
                        return askQuestion(item, original, output.question)
                    }
                    if (output.body.isBlank() || output.comment.isEmpty()) {
                        return failed(item, original, "模型输出不完整（body/comment 缺失）")
                    }
                    val modelComment = output.comment.map { it.trim().removePrefix("//").trim() }
                    if (modelComment.any { CommentProtocol.classify(it) != null }) {
                        return failed(item, original, "模型输出的注释仍包含 ai 标记，拒绝落笔")
                    }

                    // 落笔 1：只写 body，保留 ai： 注释
                    val fresh = workspace.snapshot(item.file, item.blockStartLine)
                        ?: return stale(item)
                    journal.begin(item, original)
                    queue.markStage(item.seq, RoundStage.APPLYING)
                    val bodyPlan = EditPlan(
                        target = fresh.key,
                        snapshot = fresh,
                        ops = listOf(EditOp.ReplaceBody(output.body)),
                        commandName = "Comment Companion: ${fresh.key.name}（body）",
                    )
                    when (val applied = workspace.commit(bodyPlan)) {
                        is CommitOutcome.Applied -> {
                            tracker.markOwnWrite(workspace.findVirtualFile(item.file)!!, applied.newStamp)
                            bodyApplied = true
                            journal.markApplied()
                        }
                        is CommitOutcome.Stale -> return stale(item)
                        is CommitOutcome.Failed -> return failed(item, original, "落笔失败：${applied.reason}", rollback = false)
                    }

                    if (isPaused()) {
                        rollbackBody(item, original)
                        return cancelled(item)
                    }
                    queue.markStage(item.seq, RoundStage.VERIFYING)
                    val verification = verifier.verify(item.file, baseline)
                    if (verification.ok) {
                        if (isPaused()) {
                            rollbackBody(item, original)
                            return cancelled(item)
                        }
                        // 收尾阶段：规范化注释（第二次落笔）
                        queue.markStage(item.seq, RoundStage.NORMALIZING)
                        val normalizeFresh = snapshotFor(item)
                            ?: return failed(item, original, "规范化时函数定位失败")
                        val commentLines = original.descriptionLines.map { it.trim() }
                            .filter { it.isNotEmpty() } + modelComment
                        val commentPlan = EditPlan(
                            target = normalizeFresh.key,
                            snapshot = normalizeFresh,
                            ops = listOf(EditOp.ReplaceComment(commentLines)),
                            commandName = "Comment Companion: ${normalizeFresh.key.name}（注释）",
                        )
                        when (val applied = workspace.commit(commentPlan)) {
                            is CommitOutcome.Applied -> tracker.markOwnWrite(
                                workspace.findVirtualFile(item.file)!!,
                                applied.newStamp,
                            )
                            is CommitOutcome.Stale -> return failed(item, original, "规范化时文档已变化")
                            is CommitOutcome.Failed -> return failed(item, original, "规范化落笔失败：${applied.reason}")
                        }
                        record(original, item, RoundStage.DONE)
                        journal.clear()
                        runCatching { workspace.cleanupTrailingQuestion(item.file, original.key) }
                        VerifyHooks.roundCompleted(original.key.name, "DONE", "attempts=$attempt verifier=${verifier.javaClass.simpleName} mode=${settings.snapshot().verificationMode}")
                        queue.markStage(item.seq, RoundStage.DONE)
                        return RoundResult.Done(original.key.name)
                    }

                    // 验证失败：记录新错误，回滚 body 后进入修复
                    lastErrors = verification.newErrors
                    repairErrors = verification.newErrors
                    VerifyHooks.roundCompleted(original.key.name, "VERIFY_FAILED", lastErrors.joinToString(" || ").take(500))
                    if (attempt >= policy.maxAttempts ||
                        System.currentTimeMillis() - startedAt > policy.maxDurationMillis
                    ) {
                        return failed(item, original, "验证失败：${lastErrors.firstOrNull() ?: "未知错误"}")
                    }
                    rollbackBody(item, original)
                    bodyApplied = false
                    queue.markStage(item.seq, RoundStage.REPAIRING)
                }
            }
        }
    }

    /** 行号会因前面注释被改写而漂移：锚定失败时按 instructionHash 在文件里找回。 */
    private fun snapshotFor(item: QueueItem): FunctionSnapshot? {
        // 先按指令哈希全文件找回（行号会因其他函数的注释被改写而漂移），再退回行号锚定
        val text = workspace.readText(item.file)
        if (text != null) {
            val block = CommentProtocol.directives(text.lines()).firstOrNull {
                it.directive != null && Hashing.sha256(it.directive!!.text) == item.instructionHash
            }
            if (block != null) {
                workspace.snapshot(item.file, block.startLine)?.let { return it }
            }
        }
        return workspace.snapshot(item.file, item.blockStartLine)
    }

    /**
     * 记录 AI 的提问（QuestionState + WAITING_ANSWER）。
     *
     * 注意：M3b 阶段先只记录状态并展示（面板/通知/Inlay）；
     * 把 `ai？` 写成真实注释留到下一步（需要和注释规范化的区间玩法统一）。
     */
    private fun askQuestion(item: QueueItem, original: FunctionSnapshot, question: String): RoundResult {
        val fresh = snapshotFor(item)
            ?: return failed(item, original, "提问时函数定位失败", rollback = false)
        val plan = EditPlan(
            target = fresh.key,
            snapshot = fresh,
            ops = listOf(EditOp.AppendTrailingComment("ai？ $question")),
            commandName = "Comment Companion: ${fresh.key.name}（提问）",
        )
        when (val outcome = workspace.commit(plan)) {
            is CommitOutcome.Applied -> workspace.findVirtualFile(item.file)?.let {
                tracker.markOwnWrite(it, outcome.newStamp)
            }
            is CommitOutcome.Stale -> return failed(item, original, "提问时文档已变化", rollback = false)
            is CommitOutcome.Failed -> return failed(item, original, "提问落笔失败：${outcome.reason}", rollback = false)
        }
        val record = record(original, item, RoundStage.WAITING_ANSWER)
        ledger.updateQuestion(
            QuestionState(
                key = original.key,
                questionHash = Hashing.sha256(question),
                questionLine = original.commentRawLines.size + original.commentBlockRange.first,
                instructionHash = Hashing.sha256(original.instruction.text),
                askedAtRound = record.roundNo,
                status = QuestionStatus.PENDING,
                question = question,
            ),
        )
        VerifyHooks.roundCompleted(original.key.name, "QUESTION", question.take(120))
        // 沙盒自动化：模拟用户改指令（触发 ANSWERED → 下一轮 DONE → 清理 ai？）
        if (System.getProperty("commentcompanion.verifyAnswerAfterQuestion") == "true") {
            snapshotFor(item)?.let { fresh ->
                val plan = EditPlan(
                    target = fresh.key,
                    snapshot = fresh,
                    ops = listOf(
                        EditOp.ReplaceComment(
                            fresh.descriptionLines + "ai：${fresh.instruction.text}（已答复）",
                        ),
                    ),
                    commandName = "Comment Companion: verify answer",
                )
                when (val outcome = workspace.commit(plan)) {
                    is CommitOutcome.Applied -> workspace.findVirtualFile(item.file)?.let {
                        tracker.markOwnWrite(it, outcome.newStamp)
                    }
                    else -> Unit
                }
            }
        }
        queue.markStage(item.seq, RoundStage.WAITING_ANSWER)
        return RoundResult.Question(question)
    }

    /** 用户改了指令 → ANSWERED；ai？ 注释被删 → MOOT。 */
    private fun resolveExistingQuestion(original: FunctionSnapshot) {
        val existing = ledger.question(original.key) ?: return
        if (existing.status != QuestionStatus.PENDING) return
        val questionPresent = original.commentRawLines.any { line ->
            val content = line.trimStart().removePrefix("//").trim()
            CommentProtocol.classify(content)?.first == MarkerType.QUESTION
        }
        // 简化：只按指令哈希判定；问题注释消失也不自动失效（M4 再细化）
        val status = if (Hashing.sha256(original.instruction.text) != existing.instructionHash) {
            QuestionStatus.ANSWERED
        } else {
            QuestionStatus.PENDING
        }
        if (status != existing.status) ledger.updateQuestion(existing.copy(status = status))
    }

    private fun rollbackBody(item: QueueItem, original: FunctionSnapshot): Boolean {
        val fresh = snapshotFor(item) ?: return false
        val plan = EditPlan(
            target = fresh.key,
            snapshot = fresh,
            ops = listOf(EditOp.ReplaceBody(original.bodyText)),
            commandName = "Comment Companion: ${fresh.key.name}（回滚）",
        )
        return when (val outcome = workspace.commit(plan)) {
            is CommitOutcome.Applied -> {
                workspace.findVirtualFile(item.file)?.let { tracker.markOwnWrite(it, outcome.newStamp) }
                true
            }
            else -> false
        }
    }

    private fun failed(
        item: QueueItem,
        original: FunctionSnapshot,
        message: String,
        rollback: Boolean = true,
    ): RoundResult {
        if (rollback) rollbackBody(item, original)
        journal.clear()
        record(original, item, RoundStage.FAILED, message)
        VerifyHooks.roundCompleted(original.key.name, "FAILED", message)
        queue.markStage(item.seq, RoundStage.FAILED)
        return RoundResult.Failed(message)
    }

    private fun stale(item: QueueItem): RoundResult {
        journal.clear()
        queue.markStage(item.seq, RoundStage.STALE)
        VerifyHooks.roundCompleted(item.file.relativePath, "STALE", "block=${item.blockStartLine}")
        return RoundResult.Stale
    }

    private fun cancelled(item: QueueItem, rollback: Boolean = true): RoundResult {
        journal.clear()
        queue.markStage(item.seq, RoundStage.CANCELLED)
        VerifyHooks.roundCompleted(item.file.relativePath, "CANCELLED", "")
        return RoundResult.Cancelled
    }

    private fun record(
        original: FunctionSnapshot,
        item: QueueItem,
        stage: RoundStage,
        error: String? = null,
    ): RoundRecord {
        val previous = ledger.lastRecord(original.key)
        val record = RoundRecord(
                key = original.key,
                roundNo = (previous?.roundNo ?: 0) + 1,
                instructionHash = Hashing.sha256(original.instruction.text),
                stage = stage,
                startedAt = item.enqueuedAt,
                finishedAt = System.currentTimeMillis(),
                model = settings.snapshot().engine.model,
                attempts = item.attempts + 1,
                error = error,
            )
        ledger.record(record)
        return record
    }
}
