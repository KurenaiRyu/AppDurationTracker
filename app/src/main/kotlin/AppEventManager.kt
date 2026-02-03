package moe.kurenai.app

import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime

object AppEventManager {

    private var currentApp: AppInfo? = null
    private var currentDateTime: LocalDateTime? = null

    val hourEventChannel = Channel<HourStatEvent>()
    
    fun handleEvent(event: AppEvent) {
        when (event) {
            is Foreground -> handleForeground(event)
        }
    }

    private fun handleForeground(event: Foreground) {
        if (currentApp != null && currentDateTime != null) {
            dispatchEvent(currentApp!!, currentDateTime!!, event.dateTime)
        }
        currentApp = event.appInfo
        currentDateTime = event.dateTime
    }

    private fun dispatchEvent(app: AppInfo, dateTime: LocalDateTime, nextDateTime: LocalDateTime) {
        handleHour(app, dateTime, nextDateTime)
    }

    private fun handleHour(
        app: AppInfo,
        dateTime: LocalDateTime,
        nextDateTime: LocalDateTime
    ) {
        var nextDuration = Duration.between(dateTime, nextDateTime).toMinutes()
        var count = 0
        while (nextDuration >= 0) {
            val min = nextDuration % 60
            hourEventChannel.trySend(HourStatEvent(app, min, dateTime.hour + count))
            nextDuration -= 60
            count++
        }
    }

}

sealed interface AppStatEvent

data class HourStatEvent(val appInfo: AppInfo, val durationInMin: Long, val number: Int): AppStatEvent
data class DayStatEvent(val appInfo: AppInfo, val durationInMin: Long): AppStatEvent
data class WeekStatEvent(val appInfo: AppInfo, val durationInMin: Long): AppStatEvent