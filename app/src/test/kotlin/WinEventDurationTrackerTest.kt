package moe.kurenai.app

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.pathString

class WinEventDurationTrackerTest {

    @Test
    fun testFileDescription() {
        val exePath = Path.of("D:\\worksoftware\\idea-2025.3.1.1\\bin\\idea64.exe").pathString
        println("Exe path: $exePath")
        println(WinEventDurationTracker.getFileDescription(exePath))
    }

}