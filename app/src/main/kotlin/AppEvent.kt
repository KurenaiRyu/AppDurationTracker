package moe.kurenai.app

import java.time.LocalDateTime

sealed class AppEvent

data class Foreground(val appInfo: AppInfo, val dateTime: LocalDateTime) : AppEvent()