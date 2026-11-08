package dev.commentcompanion.ide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.TrustPolicy
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.ports.CompanionSettingsSnapshot
import dev.commentcompanion.ports.EngineConfig
import dev.commentcompanion.ports.EngineProtocol
import dev.commentcompanion.ports.EngineSource
import dev.commentcompanion.ports.SettingsPort

/**
 * 设置：application 级持久化（`comment-companion.xml`）。
 * API Key 走 PasswordSafe，不进 XML。
 */
@State(name = "CommentCompanionSettings", storages = [Storage("comment-companion.xml")])
@Service(Service.Level.APP)
class CompanionSettings : PersistentStateComponent<CompanionSettings.State>, SettingsPort {

    /** XML 序列化用的可变状态（字段保持简单类型）。 */
    class State {
        var enabled: Boolean = true
        var scanIntervalMillis: Long = 2_000
        var failureRetry: String = FailureRetry.AUTO_ONCE.name
        var verificationMode: String = VerificationMode.GO_BUILD.name
        var engineSource: String = EngineSource.DSH.name
        var protocol: String = EngineProtocol.OPENAI_CHAT_COMPLETIONS.name
        var baseUrl: String = EngineProtocol.OPENAI_CHAT_COMPLETIONS.defaultBaseUrl
        var model: String = ""
        var temperature: Double = 0.2
        var timeoutMillis: Long = 60_000
        var maxTokens: Int = 4096
        var goExecutable: String = "go"
        var buildTimeoutMillis: Long = 60_000
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun engineSource(): EngineSource =
        runCatching { EngineSource.valueOf(state.engineSource) }.getOrDefault(EngineSource.DSH)

    fun setEngineSource(value: EngineSource) {
        state.engineSource = value.name
    }

    fun protocol(): EngineProtocol =
        runCatching { EngineProtocol.valueOf(state.protocol) }.getOrDefault(EngineProtocol.OPENAI_CHAT_COMPLETIONS)

    fun setProtocol(value: EngineProtocol) {
        state.protocol = value.name
        if (state.baseUrl.isBlank()) state.baseUrl = value.defaultBaseUrl
    }

    fun maxTokens(): Int = state.maxTokens

    override fun snapshot(): CompanionSettingsSnapshot = CompanionSettingsSnapshot(
        enabled = state.enabled,
        scanIntervalMillis = state.scanIntervalMillis,
        failureRetry = runCatching { FailureRetry.valueOf(state.failureRetry) }
            .getOrDefault(FailureRetry.AUTO_ONCE),
        verificationMode = System.getProperty("commentcompanion.verificationMode")
            ?.let { runCatching { VerificationMode.valueOf(it) }.getOrNull() }
            ?: runCatching { VerificationMode.valueOf(state.verificationMode) }
                .getOrDefault(VerificationMode.GO_BUILD),
        trust = TrustPolicy(),
        engineSource = engineSource(),
        protocol = protocol(),
        engine = EngineConfig(
            baseUrl = state.baseUrl.ifBlank { protocol().defaultBaseUrl },
            model = state.model,
            temperature = state.temperature,
            timeoutMillis = state.timeoutMillis,
        ),
        apiKeyConfigured = apiKey() != null,
        goExecutable = state.goExecutable.ifBlank { "go" },
        buildTimeoutMillis = state.buildTimeoutMillis,
    )

    override fun apiKey(): String? =
        PasswordSafe.instance.get(attributes())?.getPasswordAsString()

    fun setApiKey(value: String?) {
        PasswordSafe.instance.set(attributes(), value?.takeIf { it.isNotBlank() }?.let { Credentials("api-key", it) })
    }

    override fun update(transform: (CompanionSettingsSnapshot) -> CompanionSettingsSnapshot) {
        val next = transform(snapshot())
        state.enabled = next.enabled
        state.scanIntervalMillis = next.scanIntervalMillis
        state.failureRetry = next.failureRetry.name
        state.verificationMode = next.verificationMode.name
        state.engineSource = next.engineSource.name
        state.protocol = next.protocol.name
        state.baseUrl = next.engine.baseUrl
        state.model = next.engine.model
        state.temperature = next.engine.temperature
        state.timeoutMillis = next.engine.timeoutMillis
        state.goExecutable = next.goExecutable
        state.buildTimeoutMillis = next.buildTimeoutMillis
    }

    private fun attributes(): CredentialAttributes = CredentialAttributes("comment-companion", "api-key")
}
