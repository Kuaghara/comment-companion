package dev.commentcompanion.core.model

/**
 * 一次落笔的内容。执行由 WorkspacePort 负责，这里只做纯数据与纯函数。
 */
sealed interface EditOp {
    /** 用新函数体替换旧 body。 */
    data class ReplaceBody(val newText: String) : EditOp

    /** 把指令注释块替换为规范化后的注释行（不带 `//` 前缀也行，由适配器渲染）。 */
    data class ReplaceComment(val newLines: List<String>) : EditOp

    /** 同文件 import 增加（MVP 受信任级别约束）。 */
    data class AddImport(val path: String, val alias: String? = null) : EditOp

    /** import 删除（反向计划用）。 */
    data class RemoveImport(val path: String, val alias: String? = null) : EditOp
}

/**
 * 一轮要落笔的完整计划。
 *
 * - 成功路径：body（+imports）→ 验证 → 注释（收尾阶段），两次独立 `WriteCommandAction`。
 * - 失败路径：`invert()` 得到反向计划，用快照原文恢复区间；不依赖模型输出。
 */
data class EditPlan(
    val target: FunctionKey,
    val snapshot: FunctionSnapshot,
    val ops: List<EditOp>,
    val commandName: String,
) {
    init {
        require(ops.isNotEmpty()) { "计划不能为空" }
        require(target == snapshot.key) { "计划目标与快照不一致" }
    }

    /** 生成反向计划：把区间恢复为快照原文。 */
    fun invert(): EditPlan = copy(
        ops = ops.map { it.invert(snapshot) },
        commandName = "$commandName（回滚）",
    )

    companion object {
        fun EditOp.invert(snapshot: FunctionSnapshot): EditOp = when (this) {
            is EditOp.ReplaceBody -> EditOp.ReplaceBody(snapshot.bodyText)
            is EditOp.ReplaceComment -> EditOp.ReplaceComment(snapshot.commentRawLines)
            is EditOp.AddImport -> EditOp.RemoveImport(path, alias)
            is EditOp.RemoveImport -> EditOp.AddImport(path, alias)
        }
    }
}
