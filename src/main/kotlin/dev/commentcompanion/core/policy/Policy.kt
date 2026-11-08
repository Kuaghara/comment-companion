package dev.commentcompanion.core.model

/**
 * 修复策略：验证失败后的重新生成上限。
 */
data class RepairPolicy(
    val maxAttempts: Int = 5,
    val maxDurationMillis: Long = 60_000,
) {
    init {
        require(maxAttempts >= 1)
        require(maxDurationMillis > 0)
    }
}

/** 失败后的重试策略。用户选择：自动重试 1 次，再失败即停。 */
enum class FailureRetry {
    NONE,
    AUTO_ONCE,
    ALWAYS,
}

/** 验证方式开关（用户选择：做成可配置项）。 */
enum class VerificationMode {
    /** `go build ./...`（准、慢；可在设置里关闭）。 */
    GO_BUILD,

    /** 只用 GoLand daemon 诊断（快、无进程；准确性略低）。 */
    DAEMON_ONLY,
}

/** 信任级别：决定哪些改动需要先征求用户同意。 */
enum class TrustLevel { SAFE, BALANCED, TRUSTED }

data class TrustPolicy(
    val level: TrustLevel = TrustLevel.BALANCED,
    val allowImportChange: Boolean = false,
    val allowSignatureChange: Boolean = false,
)
