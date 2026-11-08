package dev.commentcompanion.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dev.commentcompanion.app.CompanionController

/** 项目打开后启动编排器（扫描 + 串行 worker）。 */
class CompanionBootstrap : StartupActivity {
    override fun runActivity(project: Project) {
        project.service<CompanionController>().start()
    }
}
