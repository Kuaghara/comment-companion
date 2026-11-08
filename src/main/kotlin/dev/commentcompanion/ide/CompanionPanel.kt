package dev.commentcompanion.ide

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.commentcompanion.app.CompanionController
import dev.commentcompanion.app.PanelSnapshot
import dev.commentcompanion.core.model.FailureRetry
import dev.commentcompanion.core.model.VerificationMode
import dev.commentcompanion.ports.EngineProtocol
import dev.commentcompanion.ports.EngineSource
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager

/**
 * 侧边窗口：底部三页导航（仪表盘 / 详细情况 / 设置）。
 *
 * 只读投影：所有状态来自 [CompanionController.panelSnapshot]；
 * 操作（暂停/恢复/保存设置）转发给 controller/settings。
 */
class CompanionPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val controller = project.service<CompanionController>()
    private val settings = service<CompanionSettings>()

    private val cards = JPanel(CardLayout())
    private val dashboard = DashboardPage()
    private val details = DetailsPage()
    private val settingsPage = SettingsPage()

    private val navDashboard = navButton("仪表盘")
    private val navDetails = navButton("详细情况")
    private val navSettings = navButton("设置")

    init {
        cards.add(dashboard, "dashboard")
        cards.add(details, "details")
        cards.add(settingsPage, "settings")

        val nav = JPanel().apply {
            layout = java.awt.GridLayout(1, 3, 0, 0)
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xDDDDDD))
            preferredSize = Dimension(0, 36)
            add(navDashboard); add(navDetails); add(navSettings)
        }
        ButtonGroup().apply { add(navDashboard); add(navDetails); add(navSettings) }

        navDashboard.addActionListener { navigate("dashboard") }
        navDetails.addActionListener { navigate("details") }
        navSettings.addActionListener { navigate("settings") }

        add(cards, BorderLayout.CENTER)
        add(nav, BorderLayout.SOUTH)

        navDashboard.isSelected = true
        Timer(500) { refresh() }.start()
        refresh()
    }

    private fun navButton(text: String) = JToggleButton(text).apply {
        isFocusable = false
        isContentAreaFilled = true
    }

    private fun navigate(page: String) {
        (cards.layout as CardLayout).show(cards, page)
        when (page) {
            "dashboard" -> navDashboard.isSelected = true
            "details" -> navDetails.isSelected = true
            else -> navSettings.isSelected = true
        }
        refresh()
    }

    private fun refresh() {
        val snapshot = controller.panelSnapshot()
        dashboard.refresh(snapshot) { navigate("details") }
        details.refresh(snapshot)
        settingsPage.refreshIfChanged()
    }

    // ---------------- Dashboard ----------------

    private inner class DashboardPage : JPanel() {
        private val circle = CircleButton("▶")
        private val status = JLabel("", SwingConstants.CENTER)
        private val pending = JLabel("", SwingConstants.CENTER)

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(24, 16, 16, 16)

            circle.alignmentX = CENTER_ALIGNMENT
            circle.addActionListener {
                if (controller.isPaused()) controller.resume() else controller.pause()
                refresh()
            }

            status.alignmentX = CENTER_ALIGNMENT
            pending.alignmentX = CENTER_ALIGNMENT
            pending.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            pending.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) = onDetails()
            })

            add(Box.createVerticalGlue())
            add(circle)
            add(Box.createVerticalStrut(20))
            add(status)
            add(Box.createVerticalStrut(48))
            add(pending)
            add(Box.createVerticalGlue())
        }

        private var onDetails: () -> Unit = {}

        fun refresh(snapshot: PanelSnapshot, openDetails: () -> Unit) {
            onDetails = openDetails
            circle.text = when {
                snapshot.paused -> "▶"
                snapshot.running -> "⏸"
                else -> "▶"
            }
            status.text = "<html><div style='text-align:center'>" +
                "<b>${escape(snapshot.statusText)}</b><br>" +
                "<span style='color:gray'>${escape(snapshot.secondaryText)}</span></div></html>"
            val count = snapshot.pending.size
            pending.text = if (count == 0) {
                "<html><span style='color:gray'>无待处理</span></html>"
            } else {
                val latest = snapshot.pending.first()
                "<html><div style='text-align:center'><b>待你处理 $count</b><br>" +
                    "<span style='color:gray'>${escape(latest.title)}</span><br>" +
                    "<span style='color:#3B82F6'>查看详情 ›</span></div></html>"
            }
        }

        private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private class CircleButton(text: String) : JButton(text) {
        init {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(88, 88)
            maximumSize = Dimension(88, 88)
            font = font.deriveFont(26f)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIManager.getColor("Button.background") ?: Color(0xF0F0F0)
            g2.fillOval(2, 2, width - 4, height - 4)
            g2.color = Color(0x3B82F6)
            g2.drawOval(2, 2, width - 4, height - 4)
            super.paintComponent(g)
        }
    }

    // ---------------- Details ----------------

    private inner class DetailsPage : JPanel(BorderLayout()) {
        private val text = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            font = font.deriveFont(12f)
        }

        init {
            add(JScrollPane(text), BorderLayout.CENTER)
        }

        fun refresh(snapshot: PanelSnapshot) {
            val current = snapshot.active
            text.text = buildString {
                appendLine("当前任务")
                if (current == null) {
                    appendLine("  （空闲）")
                } else {
                    appendLine("  ${current.file} › ${current.function}")
                    appendLine("  阶段：${current.stage.name.lowercase()} · 第 ${current.attempt} 次")
                }
                appendLine()
                appendLine("待你处理（${snapshot.pending.size}）")
                if (snapshot.pending.isEmpty()) appendLine("  （无）")
                snapshot.pending.forEach { appendLine("  • ${it.title} — ${it.detail}") }
                appendLine()
                appendLine("任务队列（${snapshot.queue.size}）")
                if (snapshot.queue.isEmpty()) appendLine("  （空）")
                snapshot.queue.forEach { row ->
                    appendLine("  • ${row.file} › ${row.function}  等待 ${row.waitSeconds}s")
                }
                appendLine()
                appendLine("最近记录（半展开，共 ${snapshot.recent.size}）")
                snapshot.recent.takeLast(3).reversed().forEach { row ->
                    val mark = when (row.stage.name) {
                        "DONE" -> "✓"
                        "FAILED" -> "✗"
                        else -> "·"
                    }
                    appendLine("  $mark ${row.file} › ${row.function}  ${row.stage.name.lowercase()}" +
                        (row.error?.let { " — ${it.take(80)}" } ?: ""))
                }
            }
            text.caretPosition = 0
        }
    }

    // ---------------- Settings ----------------

    private inner class SettingsPage : JPanel() {
        private val source = javax.swing.JComboBox(EngineSource.entries.toTypedArray())
        private val protocol = javax.swing.JComboBox(EngineProtocol.entries.toTypedArray())
        private val baseUrl = JTextField(28)
        private val model = JTextField(20)
        private val apiKey = JPasswordField(20)
        private val verification = javax.swing.JComboBox(VerificationMode.entries.toTypedArray())
        private val scanInterval = JSpinner(SpinnerNumberModel(2000, 500, 10_000, 100))
        private val retry = javax.swing.JComboBox(FailureRetry.entries.toTypedArray())
        private var loaded = false

        init {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            val form = JPanel(java.awt.GridBagLayout())
            val gbc = java.awt.GridBagConstraints().apply {
                fill = java.awt.GridBagConstraints.HORIZONTAL
                insets = java.awt.Insets(4, 4, 4, 4)
                weightx = 1.0
            }
            var row = 0
            fun addRow(label: String, component: java.awt.Component) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
                form.add(JLabel(label), gbc)
                gbc.gridx = 1; gbc.weightx = 1.0
                form.add(component, gbc)
                row++
            }
            addRow("引擎来源", source)
            addRow("协议", protocol)
            addRow("Base URL", baseUrl)
            addRow("模型", model)
            addRow("API Key", apiKey)
            addRow("验证方式", verification)
            addRow("扫描间隔(ms)", scanInterval)
            addRow("失败重试", retry)

            val save = JButton("保存")
            save.addActionListener { save() }
            gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 0.0
            form.add(save, gbc)

            add(JScrollPane(form), BorderLayout.CENTER)
            loadFromSettings()
        }

        fun refreshIfChanged() {
            if (!loaded) loadFromSettings()
        }

        private fun loadFromSettings() {
            val snapshot = settings.snapshot()
            source.selectedItem = snapshot.engineSource
            protocol.selectedItem = snapshot.protocol
            baseUrl.text = snapshot.engine.baseUrl
            model.text = snapshot.engine.model
            verification.selectedItem = snapshot.verificationMode
            scanInterval.value = snapshot.scanIntervalMillis.toInt()
            retry.selectedItem = snapshot.failureRetry
            loaded = true
        }

        private fun save() {
            val p = protocol.selectedItem as? EngineProtocol ?: EngineProtocol.OPENAI_CHAT_COMPLETIONS
            val engineSource = source.selectedItem as? EngineSource ?: EngineSource.DSH
            settings.setEngineSource(engineSource)
            settings.update { current ->
                current.copy(
                    engineSource = engineSource,
                    protocol = p,
                    engine = current.engine.copy(
                        baseUrl = baseUrl.text.trim(),
                        model = model.text.trim(),
                    ),
                    verificationMode = verification.selectedItem as? VerificationMode ?: VerificationMode.GO_BUILD,
                    scanIntervalMillis = (scanInterval.value as? Number)?.toLong() ?: 2_000L,
                    failureRetry = retry.selectedItem as? FailureRetry ?: FailureRetry.AUTO_ONCE,
                )
            }
            val key = String(apiKey.password)
            if (key.isNotBlank()) settings.setApiKey(key)
            apiKey.text = ""
            javax.swing.JOptionPane.showMessageDialog(this, "已保存")
        }
    }
}
