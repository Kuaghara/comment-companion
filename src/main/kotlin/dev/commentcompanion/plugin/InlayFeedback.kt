package dev.commentcompanion.plugin

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import java.awt.Graphics
import java.awt.Rectangle

/**
 * 最短路径的 Inlay 反馈：在光标所在行尾显示一小段文字，若干毫秒后自动消失。
 *
 * 对应设计文档里的「写完了 · 第 N 轮」短暂提示；M0 先用它验证 Inlay 在真机上可用。
 */
object InlayFeedback {

    fun show(editor: Editor, text: String, durationMillis: Int) {
        val inlay = editor.inlayModel.addAfterLineEndElement(
            editor.caretModel.offset,
            false,
            TextRenderer(text),
        ) ?: return

        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)
        alarm.addRequest({
            inlay.dispose()
            alarm.dispose()
        }, durationMillis.toLong())
    }

    private class TextRenderer(private val text: String) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val editor = inlay.editor
            val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            return editor.contentComponent.getFontMetrics(font).stringWidth(text) + 8
        }

        override fun paint(
            inlay: Inlay<*>,
            g: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes,
        ) {
            g.color = JBColor.GRAY
            val metrics = g.fontMetrics
            g.drawString(text, targetRegion.x + 4, targetRegion.y + targetRegion.height - metrics.descent)
        }
    }
}
