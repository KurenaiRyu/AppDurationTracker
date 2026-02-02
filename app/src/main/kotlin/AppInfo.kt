package moe.kurenai.app

import java.nio.file.Path


data class AppInfo(
    val name: String,
    val title: String,
    val processPath: String,
    val processId: Int,
) {
    val processName = Path.of(processPath).fileName.toString()

    companion object {
        val NULL = AppInfo("Unknown", "<unknown>", "unknown", 0)
    }
}