package moe.kurenai.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

object AppEventManager {

    private var currentApp: AppInfo? = null
    private var currentDateTime: LocalDateTime? = null

    val hourEventFlow: SharedFlow<HourStatEvent>
        field = MutableSharedFlow<HourStatEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val dayEventFlow: SharedFlow<DayStatEvent>
        field = MutableSharedFlow<DayStatEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val totalEventFlow: SharedFlow<TotalStatEvent>
        field = MutableSharedFlow<TotalStatEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val sessionEventFlow: SharedFlow<SessionStatEvent>
        field = MutableSharedFlow<SessionStatEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
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
        sessionEventFlow.tryEmit(SessionStatEvent(app, duration, dateTime, nextDateTime))
//        handleHour(app, dateTime, nextDateTime, duration)
        handleDay(app, dateTime, duration)
        handleTotal(app, duration)
    }

    private fun handleDay(
        app: AppInfo,
        dateTime: LocalDateTime,
        duration: Long
    ) {
        var nextDuration = duration
        var count = 0
        while (nextDuration >= 0) {
            val min = nextDuration % (60 * 24)
            dayEventFlow.tryEmit(DayStatEvent (app, min, dateTime.toLocalDate()))
            nextDuration -= 60 * 24
            count++
        }
    }

    private fun handleTotal(
        app: AppInfo,
        duration: Long
    ) {
        totalEventFlow.tryEmit(TotalStatEvent (app, duration))
    }

    private fun handleHour(
        app: AppInfo,
        dateTime: LocalDateTime,
        duration: Long
    ) {
        var nextDuration = duration
        var count = 0
        var time = dateTime
        while (nextDuration >= 0) {
            val min = nextDuration % 60
            hourEventFlow.tryEmit(HourStatEvent(app, min, time.toLocalDate(), time.hour))
            nextDuration -= 60
            count++
            time = time.plusHours(1)
        }
    }

}

sealed interface AppStatEvent

data class HourStatEvent(val appInfo: AppInfo, val durationInMin: Long, val date: LocalDate, val number: Int): AppStatEvent
data class DayStatEvent(val appInfo: AppInfo, val durationInMin: Long, val date: LocalDate): AppStatEvent
data class WeekStatEvent(val appInfo: AppInfo, val durationInMin: Long): AppStatEvent
data class TotalStatEvent(val appInfo: AppInfo, val durationInMin: Long): AppStatEvent
data class SessionStatEvent(val appInfo: AppInfo, val durationInMin: Long, val start: LocalDateTime, val end: LocalDateTime): AppStatEvent