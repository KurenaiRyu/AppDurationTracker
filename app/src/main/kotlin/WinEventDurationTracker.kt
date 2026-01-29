package moe.kurenai.app

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * 使用 JDK25 Foreign API 监听前台窗口切换（EVENT_SYSTEM_FOREGROUND）并统计每个窗口累计停留时间（ms）。
 *
 * 说明：
 * - 依赖 JDK 25 (java.lang.foreign API)
 * - 在 Windows x64 上运行
 */
object WinEventDurationTracker {
    // WinEvent 常量
    private const val EVENT_SYSTEM_FOREGROUND = 0x0003
    private const val WINEVENT_OUTOFCONTEXT = 0x0000
    private const val WINEVENT_SKIPOWNPROCESS = 0x0002

    // Foreign helper
    private val linker: Linker = Linker.nativeLinker()
    private val user32: SymbolLookup = SymbolLookup.libraryLookup("user32", Arena.global())

    // Downcall handles
    private val setWinEventHookHandle: MethodHandle by lazy { downcall("SetWinEventHook", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)) }
    private val unhookWinEventHandle: MethodHandle by lazy { downcall("UnhookWinEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val getWindowTextWHandle: MethodHandle by lazy { downcall("GetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) }
    private val getWindowTextLengthWHandle: MethodHandle by lazy { downcall("GetWindowTextLengthW", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val getWindowThreadProcessIdHandle: MethodHandle by lazy { downcall("GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)) }
    private val getMessageWHandle: MethodHandle by lazy { downcall("GetMessageW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)) }

    // Hook handle (MemoryAddress stored in AtomicReference)
    private val hookHandle = AtomicReference<MemorySegment?>(null)

    // Statistics: title@pid -> accumulated ms
    private val durations = ConcurrentHashMap<String, Long>()

    // Current active window and start time
    @Volatile
    private var currentKey: String? = null
    @Volatile
    private var currentStart: Instant? = null

    // Global MemorySession for allocations and stubs (kept alive while tracking)
    private val global: Arena = Arena.global()

    // Create a downcall MethodHandle for a native symbol name and descriptor
    private fun downcall(name: String, fd: FunctionDescriptor): MethodHandle {
        val sym = user32.find(name).orElseThrow { RuntimeException("Symbol not found: $name") }
        return linker.downcallHandle(sym, fd)
    }

    // Create upcall stub for WinEventProc and return its MemorySegment (function pointer)
    private fun createWinEventProcStub(): MemorySegment {
        // Java method handle pointing to our static callback
        val mh = MethodHandles.lookup().findStatic(
            WinEventDurationTracker::class.java,
            "winEventProc",
            MethodType.methodType(
                Void.TYPE,
                MemorySegment::class.java, // HWINEVENTHOOK
                Int::class.javaPrimitiveType, // EVENT
                MemorySegment::class.java, // HWND
                Int::class.javaPrimitiveType, // idObject
                Int::class.javaPrimitiveType, // idChild
                Int::class.javaPrimitiveType, // dwEventThread
                Int::class.javaPrimitiveType  // dwmsEventTime
            )
        )
        val fd = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        return linker.upcallStub(mh, fd, global)
    }

    // Native callback invoked by Windows when an event occurs
    @JvmStatic
    fun winEventProc(hWinEventHook: MemorySegment, event: Int, hwnd: MemorySegment, idObject: Int, idChild: Int, dwEventThread: Int, dwmsEventTime: Int) {
        println("Event $event")
        if (event != EVENT_SYSTEM_FOREGROUND) return
        try {
            val key = getWindowKey(hwnd)
            val now = Instant.now()
            synchronized(this) {
                val prev = currentKey
                val prevStart = currentStart
                if (prev != null && prevStart != null) {
                    val elapsed = Duration.between(prevStart, now).toMillis()
                    durations.merge(prev, elapsed) { a, b -> a + b }
                }
                currentKey = key
                currentStart = now
            }
            println("Foreground -> $key at $now")
        } catch (t: Throwable) {
            System.err.println("winEventProc error: ${t.message}")
            t.printStackTrace()
        }
    }

    // Build a readable key string from hwnd: "title@pid"
    private fun getWindowKey(hwnd: MemorySegment): String {
        if (hwnd == MemorySegment.NULL) return "<null>"
        try {
            // Get title length
            val lenObj = getWindowTextLengthWHandle.invokeExact(hwnd) as Int
            val bufChars = (lenObj + 1).coerceAtLeast(256)
            val bufBytes = bufChars * 2 // WCHAR = 2 bytes
            val mem = global.allocate(bufBytes.toLong())
            // Call GetWindowTextW(hwnd, mem, bufChars)
            getWindowTextWHandle.invokeExact(hwnd, mem, bufChars) as Int
            val title = readWideString(mem, bufChars)
            // pid
            val pidBuf = global.allocate(JAVA_INT.byteSize())
            // Some GetWindowThreadProcessId declarations return thread id and set pidBuf
            getWindowThreadProcessIdHandle.invokeExact(hwnd, pidBuf) as Int
            val pid = pidBuf.get(JAVA_INT, 0)
            return "$title@$pid"
        } catch (t: Throwable) {
            System.err.println(t.message)
            return "<unknown>@0"
        }
    }

    // Read a null-terminated UTF-16 (wide) string from MemorySegment (maxChars length)
    private fun readWideString(seg: MemorySegment, maxChars: Int): String {
        val sb = StringBuilder()
        for (i in 0 until maxChars) {
            val ch = seg.get(ValueLayout.JAVA_SHORT, (i * 2).toLong()).toInt() and 0xffff
            if (ch == 0) break
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    // Start tracking: create upcall stub, register hook, and setup shutdown hook
    fun startTracking() {
        val stubSegment = createWinEventProcStub()
        val flags = WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS
        val res = setWinEventHookHandle.invokeExact(
            EVENT_SYSTEM_FOREGROUND,
            EVENT_SYSTEM_FOREGROUND,
            MemorySegment.NULL,
            stubSegment,
            0,
            0,
            flags
        ) as MemorySegment
        hookHandle.set(res)
        println("Hook set: $res")

        // On JVM exit, ensure unhook and print stats
        Runtime.getRuntime().addShutdownHook(Thread {
            stopTracking()
        })
    }

    // Stop tracking: unhook and print accumulated durations
    fun stopTracking() {
        try {
            val h = hookHandle.get()
            if (h != null && h != MemorySegment.NULL) {
                unhookWinEventHandle.invokeExact(h)
                hookHandle.set(null)
                println("Hook removed")
            }
        } catch (t: Throwable) {
            System.err.println("Error unhooking: ${t.message}")
        }

        // finalize current window
        val now = Instant.now()
        val prev = currentKey
        val prevStart = currentStart
        if (prev != null && prevStart != null) {
            val elapsed = Duration.between(prevStart, now).toMillis()
            durations.merge(prev, elapsed) { a, b -> a + b }
        }

        println("=== Accumulated durations (ms) ===")
        durations.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            println("$k -> $v ms")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting WinEventDurationTracker (JDK 25) - Press Ctrl+C to stop")
        try {
            startTracking()
        } catch (t: Throwable) {
            System.err.println("Failed to start hook: ${t.message}")
            t.printStackTrace()
            exitProcess(1)
        }

        // Keep JVM alive. SetWinEventHook with OUTOFCONTEXT will deliver callbacks without requiring message loop,
        // but we keep the process alive and responsive to Ctrl+C.
        try {
            val msg: MemorySegment = global.allocate(48)

            while ((getMessageWHandle.invokeExact(msg, MemorySegment.NULL, 0, 0) as Int) != 0) {
                println("MSG: ${msg.asByteBuffer().getInt()}")
            }
        } catch (ie: InterruptedException) {
            // exit loop
        } finally {
            stopTracking()
        }
    }
}