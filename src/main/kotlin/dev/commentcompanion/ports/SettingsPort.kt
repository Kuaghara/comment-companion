package dev.commentcompanion.ports

import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.TrustPolicy
import dev.commentcompanion.core.model.VerificationMode

/** 引擎配置（BYOK：OpenAI 兼容端点）。 */
data class EngineConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val temperature: Double = 0.2,
    val timeoutMillis: Long = 60_000,
)

/** 一轮运行所需的设置快照（不可变，读一次用一轮）。 */
data class CompanionSettingsSnapshot(
    val enabled: Boolean = true,
    /** 空闲扫描周期；用户定为 2 秒。 */
    val scanIntervalMillis: Long = 2_000,
    /** 失败后自动重试一次（用户选择）。 */
    val failureRetry: FailureRetry = FailureRetry.AUTO_ONCE,
    /** 验证方式：go build 还是只用 daemon（用户要求做成开关，默认 go build）。 */
    val verificationMode: VerificationMode = VerificationMode.GO_BUILD,
    val trust: TrustPolicy = TrustPolicy(),
    val engine: EngineConfig = EngineConfig(),
    val apiKeyConfigured: Boolean = false,
)

interface SettingsPort {
    fun snapshot(): CompanionSettingsSnapshot

    fun apiKey(): String?

    fun update(transform: (CompanionSettingsSnapshot) -> CompanionSettingsSnapshot)
}
