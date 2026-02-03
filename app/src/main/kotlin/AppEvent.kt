package moe.kurenai.app

import java.time.LocalDateTime

sealed interface AppEvent

data class Foreground(val appInfo: AppInfo, val dateTime: LocalDateTime) : AppEvent