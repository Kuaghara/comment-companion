package dev.commentcompanion.ide

import com.intellij.openapi.components.Service
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.TrustPolicy
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.ports.CompanionSettingsSnapshot
import dev.commentcompanion.ports.EngineConfig
import dev.commentcompanion.ports.SettingsPort

/** 引擎协议（D15）。 */
enum class EngineProtocol(val label: String) {
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions"),
    OPENAI_RESPONSES("OpenAI Responses"),
    ANTHROPIC_MESSAGES("Anthropic Messages"),
}

/**
 * 设置（M1b 先用内存版；M2 换成 PersistentStateComponent + PasswordSafe）。
 */
@Service(Service.Level.APP)
class CompanionSettings : SettingsPort {

    private var enabled = true
    private var scanIntervalMillis = 2_000L
    private var failureRetry = FailureRetry.AUTO_ONCE
    private var verificationMode = VerificationMode.GO_BUILD
    private var trust = TrustPolicy()
    private var protocol = EngineProtocol.OPENAI_CHAT_COMPLETIONS
    private var baseUrl = "https://api.openai.com/v1"
    private var model = ""
    private var temperature = 0.2
    private var timeoutMillis = 60_000L
    private var maxTokens = 4096
    private var apiKey: String? = null

    fun protocol(): EngineProtocol = protocol

    fun setProtocol(value: EngineProtocol) {
        protocol = value
    }

    fun maxTokens(): Int = maxTokens

    override fun snapshot(): CompanionSettingsSnapshot {
        val snapshot = CompanionSettingsSnapshot(
            enabled = enabled,
            scanIntervalMillis = scanIntervalMillis,
            failureRetry = failureRetry,
            verificationMode = verificationMode,
            trust = trust,
            engine = EngineConfig(
                baseUrl = baseUrl,
                model = model,
                temperature = temperature,
                timeoutMillis = timeoutMillis,
            ),
            apiKeyConfigured = apiKey != null,
        )
        return snapshot
    }

    override fun apiKey(): String? = apiKey

    override fun update(transform: (CompanionSettingsSnapshot) -> CompanionSettingsSnapshot) {
        val next = transform(snapshot())
        enabled = next.enabled
        scanIntervalMillis = next.scanIntervalMillis
        failureRetry = next.failureRetry
        verificationMode = next.verificationMode
        trust = next.trust
        baseUrl = next.engine.baseUrl
        model = next.engine.model
        temperature = next.engine.temperature
        timeoutMillis = next.engine.timeoutMillis
    }

    fun setApiKey(value: String?) {
        apiKey = value
    }
}
