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
object EngineFactory {

    fun create(settings: SettingsPort): EnginePort {
        val snapshot = settings.snapshot()
        val forcedFake = System.getProperty("commentcompanion.engine") == "fake"
        if (forcedFake) return FakeEngine()

        // 默认：从 DSH 读配置（provider / protocol / baseURL / model / key）
        if (snapshot.engineSource == EngineSource.DSH) {
            val dsh = DshConfig.resolve()
            if (dsh != null && dsh.model.isNotBlank()) {
                return HttpEngine(
                    protocol = dsh.protocol,
                    config = EngineConfig(
                        baseUrl = dsh.baseUrl,
                        model = dsh.model,
                        temperature = snapshot.engine.temperature,
                        timeoutMillis = snapshot.engine.timeoutMillis,
                    ),
                    apiKey = dsh.apiKey ?: "",
                )
            }
        }

        val key = settings.apiKey()
        if (snapshot.engine.model.isBlank() || key.isNullOrBlank()) {
            return FakeEngine()
        }
        return HttpEngine(
            protocol = snapshot.protocol,
            config = snapshot.engine,
            apiKey = key,
        )
    }
}
