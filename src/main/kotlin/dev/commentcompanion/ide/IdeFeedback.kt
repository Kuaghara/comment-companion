package dev.commentcompanion.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import dev.commentcompanion.core.model.FileKey
import dev.commentcompanion.plugin.InlayFeedback
import dev.commentcompanion.ports.FeedbackPort

/**
 * FeedbackPort 的 GoLand 实现：通知 + Inlay。
 * 侧边窗口（M3）之后接 PanelSnapshot；M1b 先用最轻的反馈。
 */
class IdeFeedback(private val project: Project) : FeedbackPort {

    override fun showTransientHint(file: FileKey, line: Int, text: String, ttlMillis: Long) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFiles.find(project, file) ?: return@invokeLater
            val editor = FileEditorManager.getInstance(project)
                .getAllEditors(vf)
                .filterIsInstance<TextEditor>()
                .firstOrNull()?.editor ?: return@invokeLater
            InlayFeedback.show(editor, text, ttlMillis.toInt())
        }
    }

    override fun setBusy(file: FileKey, label: String) {
        showTransientHint(file, 0, label, 60_000)
    }

    override fun clearBusy(file: FileKey) = Unit

    override fun updateStatus(pending: Int, stageLabel: String?, lastError: String?) = Unit

    override fun notifyInfo(title: String, message: String) {
        notify(title, message, NotificationType.INFORMATION)
    }

    override fun notifyError(title: String, message: String) {
        notify(title, message, NotificationType.WARNING)
    }

    private fun notify(title: String, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CommentCompanion")
            .createNotification(title, message, type)
            .notify(project)
    }
}

/** FileKey → VirtualFile 的轻量查找（避免在 Feedback 里重复依赖 IdeWorkspace）。 */
internal object LocalFiles {
    fun find(project: Project, file: FileKey): com.intellij.openapi.vfs.VirtualFile? {
        val base = project.basePath ?: return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$base/${file.relativePath}")
    }
}
