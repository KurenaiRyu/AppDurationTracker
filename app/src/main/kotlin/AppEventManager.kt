package moe.kurenai.app

import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.time.LocalDateTime

object AppEventManager {

    private var currentApp: AppInfo? = null
    private var currentDateTime: LocalDateTime? = null

    val hourEventChannel = Channel<HourStatEvent>()
    val dayEventChannel = Channel<DayStatEvent>()
    val sessionEventChannel = Channel<SessionStatEvent>()
    
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
        val duration = Duration.between(dateTime, nextDateTime).toMinutes()
        sessionEventChannel.trySend(SessionStatEvent(app, duration, dateTime, nextDateTime))
//        handleHour(app, dateTime, nextDateTime, duration)
        handleDay(app, dateTime, nextDateTime, duration)
    }

    private fun handleDay(
        app: AppInfo,
        dateTime: LocalDateTime,
        nextDateTime: LocalDateTime,
        duration: Long
    ) {
        var nextDuration = duration
        var count = 0
        while (nextDuration >= 0) {
            val min = nextDuration % 60 * 24
            dayEventChannel.trySend(DayStatEvent (app, min, dateTime.dayOfYear + count))
            nextDuration -= 60 * 24
            count++
        }
    }

    private fun handleHour(
        app: AppInfo,
        dateTime: LocalDateTime,
        nextDateTime: LocalDateTime,
        duration: Long
    ) {
        var nextDuration = duration
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
data class DayStatEvent(val appInfo: AppInfo, val durationInMin: Long, val number: Int): AppStatEvent
data class WeekStatEvent(val appInfo: AppInfo, val durationInMin: Long): AppStatEvent
data class SessionStatEvent(val appInfo: AppInfo, val durationInMin: Long, val start: LocalDateTime, val end: LocalDateTime): AppStatEvent