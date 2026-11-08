package dev.commentcompanion.engine

import dev.commentcompanion.core.prompt.EngineOutput
import dev.commentcompanion.core.prompt.EngineRequest
import dev.commentcompanion.core.prompt.EngineResult
import dev.commentcompanion.ports.EnginePort

/**
 * 确定性假引擎：M1b 用它验证「快照 → 生成 → 落笔 → 规范化 → 记账」整条链路，
 * 不产生网络请求。M2 接入真实引擎后，它仍用于测试。
 */
class FakeEngine : EnginePort {

    override suspend fun generate(request: EngineRequest): EngineResult {
        val instruction = request.payload.instruction.trim()
        val body = "{\n\treturn 0\n}"
        val commentText = instruction.ifBlank { "TODO" }
        // 注释行由调用方渲染为 // 前缀；这里保证不会重新构成标记
        val safeComment = if (commentText.startsWith("ai：") || commentText.startsWith("ai:")) {
            commentText.removePrefix("ai：").removePrefix("ai:").trim()
        } else {
            commentText
        }
        return EngineResult.Success(
            EngineOutput(
                body = body,
                comment = listOf(safeComment),
            ),
        )
    }
}
