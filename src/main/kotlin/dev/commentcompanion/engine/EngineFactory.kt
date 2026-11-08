package dev.commentcompanion.engine

import dev.commentcompanion.ide.DshConfig
import dev.commentcompanion.ports.EngineConfig
import dev.commentcompanion.ports.EnginePort
import dev.commentcompanion.ports.EngineSource
import dev.commentcompanion.ports.SettingsPort

/**
 * 引擎选择：
 * - 设置了 `-Dcommentcompanion.engine=fake` → FakeEngine（沙盒/测试）
 * - 未配置模型或 API Key → FakeEngine（保证链路可跑通，不报错）
 * - 否则按协议创建 HttpEngine
 */
data class ResolvedEngine(val engine: EnginePort, val config: EngineConfig)

object EngineFactory {

    /** 返回引擎与「本次真正生效的配置」（DSH 模式用 DSH 的 model/baseUrl）。 */
    fun resolve(settings: SettingsPort): ResolvedEngine = createInternal(settings)

    fun create(settings: SettingsPort): EnginePort = createInternal(settings).engine

    private fun createInternal(settings: SettingsPort): ResolvedEngine {
        val snapshot = settings.snapshot()
        val forcedFake = System.getProperty("commentcompanion.engine") == "fake"
        val fallbackConfig = snapshot.engine
        if (forcedFake) return ResolvedEngine(FakeEngine(), fallbackConfig)

        // 默认：从 DSH 读配置（provider / protocol / baseURL / model / key）
        if (snapshot.engineSource == EngineSource.DSH) {
            val dsh = DshConfig.resolve()
            if (dsh != null && dsh.model.isNotBlank()) {
                val effective = EngineConfig(
                    baseUrl = dsh.baseUrl,
                    model = dsh.model,
                    temperature = snapshot.engine.temperature,
                    timeoutMillis = snapshot.engine.timeoutMillis,
                )
                return ResolvedEngine(
                    engine = HttpEngine(dsh.protocol, effective, dsh.apiKey ?: ""),
                    config = effective,
                )
            }
        }

        val key = settings.apiKey()
        if (snapshot.engine.model.isBlank() || key.isNullOrBlank()) {
            return ResolvedEngine(FakeEngine(), fallbackConfig)
        }
        return ResolvedEngine(
            engine = HttpEngine(snapshot.protocol, fallbackConfig, key),
            config = fallbackConfig,
        )
    }
}
