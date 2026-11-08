package dev.commentcompanion.engine

import dev.commentcompanion.ports.EnginePort
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
        val key = settings.apiKey()
        val forcedFake = System.getProperty("commentcompanion.engine") == "fake"
        if (forcedFake || snapshot.engine.model.isBlank() || key.isNullOrBlank()) {
            return FakeEngine()
        }
        return HttpEngine(
            protocol = snapshot.protocol,
            config = snapshot.engine,
            apiKey = key,
        )
    }
}
