package dev.commentcompanion.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.commentcompanion.app.CompanionController
import java.awt.Component
import javax.swing.Timer

class CompanionStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Comment Companion"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = CompanionStatusBarWidget(project)

    companion object {
        const val ID = "CommentCompanionStatus"
    }
}

/** 状态栏：待处理数量 + 暂停状态（只读，文本呈现）。 */
class CompanionStatusBarWidget(private val project: Project) : StatusBarWidget {

    private var statusBar: StatusBar? = null
    private var text: String = "CC 0"
    private val timer = Timer(1000) { refresh() }

    private val presentation = object : StatusBarWidget.TextPresentation {
        override fun getText(): String = text
        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT
        override fun getTooltipText(): String = "Comment Companion：待处理数量"
    }

    override fun ID(): String = CompanionStatusBarWidgetFactory.ID

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = presentation

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        timer.start()
        refresh()
    }

    override fun dispose() {
        timer.stop()
        statusBar = null
    }

    private fun refresh() {
        runCatching {
            val controller = project.service<CompanionController>()
            val paused = if (controller.isPaused()) "⏸ " else ""
            text = "${paused}CC ${controller.queueSize()}"
            statusBar?.updateWidget(ID())
        }
    }
}
