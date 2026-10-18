package dev.commentcompanion.plugin

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.goide.psi.GoFile
import dev.commentcompanion.core.protocol.CommentProtocol

/**
 * M0 最小交互：扫描当前 Go 文件里的 `ai：` 指令数量，用通知 + 行尾 Inlay 反馈。
 *
 * 它同时承担真机验证职责：证明 Go PSI（`GoFile.functions`）与 Inlay 在运行时可用。
 */
class ScanFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val directives = CommentProtocol.directives(file.text.lines()).mapNotNull { it.directive }
        val goFunctions = (file as? GoFile)?.functions?.size ?: 0

        val message = "识别到 ${directives.size} 条 ai：指令，${goFunctions} 个 Go 函数"
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CommentCompanion")
            .createNotification("Comment Companion", message, NotificationType.INFORMATION)
            .notify(project)

        if (editor != null) {
            InlayFeedback.show(editor, "扫描：${directives.size} 条指令", 2500)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PSI_FILE) is GoFile
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
