package moe.kurenai.app

import java.time.LocalDateTime

object AppEventHandler {

    private var currentApp: AppInfo? = null
    private var currentDateTime: LocalDateTime? = null

    fun handleEvent(event: AppEvent) {
        when (event) {
            is Foreground -> handleForeground(event)
        }
    }

    private fun handleForeground(event: Foreground) {
        if (currentApp != null && currentDateTime != null) {
            handlePrev(currentApp!!, currentDateTime!!, event.dateTime)
        }
        currentApp = event.appInfo
        currentDateTime = event.dateTime
    }

    private fun handlePrev(app: AppInfo, dateTime: LocalDateTime, nextDateTime: LocalDateTime) {
        if (dateTime.hour != nextDateTime.hour) {

        }
    }

}