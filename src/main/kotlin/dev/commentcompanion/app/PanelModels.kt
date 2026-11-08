package dev.commentcompanion.app

import dev.commentcompanion.core.model.RoundStage
import dev.commentcompanion.ports.CompanionSettingsSnapshot

/** 面板只读投影：controller 生成，UI 只消费。 */
data class PanelSnapshot(
    val statusText: String,
    val secondaryText: String,
    val running: Boolean,
    val paused: Boolean,
    val pending: List<PendingRow>,
    val active: ActiveRow?,
    val queue: List<QueueRow>,
    val recent: List<RecentRow>,
    val settings: CompanionSettingsSnapshot,
)

data class PendingRow(
    val title: String,
    val detail: String,
)

data class ActiveRow(
    val file: String,
    val function: String,
    val stage: RoundStage,
    val attempt: Int,
)

data class QueueRow(
    val file: String,
    val function: String,
    val instructionHash: String,
    val waitSeconds: Long,
)

data class RecentRow(
    val file: String,
    val function: String,
    val stage: RoundStage,
    val attempts: Int,
    val error: String?,
)
