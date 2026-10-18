package dev.commentcompanion.ports

import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.core.prompt.EngineRequest
import dev.commentcompanion.core.prompt.EngineResult

/**
 * 模型能力边界。M2 用 OpenAI 兼容实现；以后接 Ayane/Koog 只替换适配器。
 */
interface EnginePort {
    suspend fun generate(request: EngineRequest): EngineResult
}

/** 用户可见反馈（全部由适配器切到 EDT）。 */
interface FeedbackPort {
    /** 光标行尾的短提示（自动消失）。 */
    fun showTransientHint(file: FileKey, line: Int, text: String, ttlMillis: Long)

    /** 文件级忙碌状态（状态栏/面板显示）。 */
    fun setBusy(file: FileKey, label: String)

    fun clearBusy(file: FileKey)

    fun updateStatus(pending: Int, stageLabel: String?, lastError: String?)

    fun notifyInfo(title: String, message: String)

    fun notifyError(title: String, message: String)
}
