package dev.commentcompanion.ports

import dev.commentcompanion.core.model.EditPlan
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.model.FunctionKey
import dev.commentcompanion.core.model.FunctionSnapshot

/** 文档快照（文本 + 版本）。 */
data class DocumentSnapshot(
    val file: FileKey,
    val text: String,
    val stamp: Long,
)

sealed interface CommitOutcome {
    data class Applied(val newStamp: Long) : CommitOutcome
    data class Stale(val currentStamp: Long) : CommitOutcome
    data class Failed(val reason: String) : CommitOutcome
}

/** 内存里的候选代码语法检查结果（不落文档）。 */
data class PreviewResult(
    val hasSyntaxErrors: Boolean,
    val errors: List<String>,
)

/**
 * 工作区能力：IDE 适配层实现。所有方法都由调用方决定线程（读取在 ReadAction 内，
 * 提交走 EDT + WriteCommandAction）。
 */
interface WorkspacePort {

    /** 当前打开着的、可被扫描的文件（MVP：只扫打开文档）。 */
    fun openDocuments(): List<FileKey>

    fun documentStamp(file: FileKey): Long?

    fun readText(file: FileKey): String?

    /** 以注释块首行为锚，定位其下方函数并生成快照。 */
    fun snapshot(file: FileKey, blockStartLine: Int): FunctionSnapshot?

    /** 提交编辑计划（单个 WriteCommandAction，保证一次撤销）。 */
    fun commit(plan: EditPlan): CommitOutcome

    /** 在内存中解析候选代码，用于落笔前语法校验。 */
    fun previewParse(file: FileKey, candidateText: String): PreviewResult

    /** 清理函数体之后的 `ai？` 注释（问题已回答/失效后调用）。 */
    fun cleanupTrailingQuestion(file: FileKey, key: FunctionKey): Boolean
}
