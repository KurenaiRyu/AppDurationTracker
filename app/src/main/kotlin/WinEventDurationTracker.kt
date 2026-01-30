package moe.kurenai.app

import java.lang.foreign.*
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.pathString
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
    private const val PROCESS_QUERY_INFORMATION = 0x0400
    private const val PROCESS_VM_READ = 0x0010

    // Foreign helper
    private val linker: Linker = Linker.nativeLinker()
    private val user32: SymbolLookup = SymbolLookup.libraryLookup("user32", Arena.global())
    private val kernel32: SymbolLookup = SymbolLookup.libraryLookup("kernel32", Arena.global())
    private val psapi: SymbolLookup = SymbolLookup.libraryLookup("psapi", Arena.global())
    private val version: SymbolLookup = SymbolLookup.libraryLookup("version", Arena.global())

    // Downcall handles
    private val setWinEventHookHandle: MethodHandle by lazy {
        downcall(
            user32, "SetWinEventHook",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        )
    }
    private val unhookWinEventHandle: MethodHandle by lazy {
        downcall(
            user32, "UnhookWinEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS)
        )
    }
    private val getWindowTextWHandle: MethodHandle by lazy {
        downcall(
            user32, "GetWindowTextW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
        )
    }
    private val getWindowTextLengthWHandle: MethodHandle by lazy {
        downcall(
            user32, "GetWindowTextLengthW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS)
        )
    }
    private val getWindowThreadProcessIdHandle: MethodHandle by lazy {
        downcall(
            user32, "GetWindowThreadProcessId",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
        )
    }
    private val getMessageWHandle: MethodHandle by lazy {
        downcall(
            user32, "GetMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)
        )
    }
    private val getClassNameWHandle: MethodHandle by lazy {
        downcall(
            user32, "GetClassNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
        )
    }
    private val openProcessHandle: MethodHandle by lazy {
        downcall(
            kernel32, "OpenProcess",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)
        )
    }
    private val closeHandleHandle: MethodHandle by lazy {
        downcall(
            kernel32, "CloseHandle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS)
        )
    }
    private val getModuleBaseNameWHandle: MethodHandle by lazy {
        downcall(
            psapi, "GetModuleBaseNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
        )
    }
    private val queryFullProcessImageNameWHandle: MethodHandle by lazy {
        downcall(
            kernel32, "QueryFullProcessImageNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)
        )
    }
    private val getFileVersionInfoSizeWHandle: MethodHandle by lazy {
        downcall(
            version, "GetFileVersionInfoSizeW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
        )
    }
    private val getFileVersionInfoWHandle: MethodHandle by lazy {
        downcall(
            version, "GetFileVersionInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
        )
    }
    private val verQueryValueWHandle: MethodHandle by lazy {
        downcall(
            version, "VerQueryValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
        )
    }

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
    private fun downcall(lookUp: SymbolLookup, name: String, fd: FunctionDescriptor): MethodHandle {
        val sym = lookUp.find(name).orElseThrow { RuntimeException("Symbol not found: $name") }
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
    fun winEventProc(
        hWinEventHook: MemorySegment,
        event: Int,
        hwnd: MemorySegment,
        idObject: Int,
        idChild: Int,
        dwEventThread: Int,
        dwmsEventTime: Int
    ) {
        println("Event $event")
        if (event != EVENT_SYSTEM_FOREGROUND) return
        try {
            val info = getWindowInfo(hwnd)
            val now = Instant.now()
            synchronized(this) {
                val prev = currentKey
                val prevStart = currentStart
                if (prev != null && prevStart != null) {
                    val elapsed = Duration.between(prevStart, now).toMillis()
                    durations.merge(prev, elapsed) { a, b -> a + b }
                }
                currentKey = info.processName
                currentStart = now
            }
            println("Foreground -> $info at $now")
        } catch (t: Throwable) {
            System.err.println("winEventProc error: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun getWindowInfo(hwnd: MemorySegment): AppInfo {
        if (hwnd == MemorySegment.NULL) return AppInfo.NULL
        try {
            // Get title length
            val lenObj = getWindowTextLengthWHandle.invokeExact(hwnd) as Int
            val bufChars = (lenObj + 1).coerceAtLeast(256)
            val bufBytes = bufChars * 2 // WCHAR = 2 bytes
            val mem = global.allocate(bufBytes.toLong())
            // Call GetWindowTextW(hwnd, mem, bufChars)
            getWindowTextWHandle.invokeExact(hwnd, mem, bufChars) as Int
            val title = mem.getString(0, Charsets.UTF_16LE)
            // pid
            val pidBuf = global.allocate(JAVA_INT.byteSize())
            // Some GetWindowThreadProcessId declarations return thread id and set pidBuf
            getWindowThreadProcessIdHandle.invokeExact(hwnd, pidBuf) as Int
            val pid = pidBuf.get(JAVA_INT, 0)

            val hProcess = openProcessHandle.invokeExact(
                PROCESS_QUERY_INFORMATION or PROCESS_VM_READ,
                0,
                pid
            ) as MemorySegment

            val processPath = if ((0 == hProcess.address().toInt())) null
            else {
                try {
                    val buf: MemorySegment = global.allocate(1024 * 2)
                    val size: MemorySegment = global.allocate(JAVA_INT)
                    size.set(JAVA_INT, 0, 1024)

                    val ok = queryFullProcessImageNameWHandle.invokeExact(
                        hProcess,
                        0,
                        buf,
                        size
                    ) as Int

                    if (ok == 0) null
                    else Path.of(buf.getString(0, Charsets.UTF_16LE))
                } finally {
                    closeHandleHandle.invokeExact(hProcess) as Int
                }
            }

            val name = if (processPath != null) {
                getFileDescription(processPath.pathString) ?: "Unknown"
            } else "Unknown"


            val classNameBuf: MemorySegment = global.allocate(256 * 2)
            getClassNameWHandle.invokeExact(hwnd, classNameBuf, 256) as Int
            val className = classNameBuf.getString(0, Charsets.UTF_16LE)
            return AppInfo(
                name = name,
                title = title,
                className = className,
                processPath = processPath,
                processId = pid
            )
        } catch (t: Throwable) {
            System.err.println(t.message)

            return AppInfo.NULL
        }
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
                unhookWinEventHandle.invokeExact(h) as Int
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

    @Throws(Throwable::class)
    fun getFileDescription(exePath: String): String? {
        // wchar_t* path
        val path: MemorySegment = global.allocateFrom(exePath, Charsets.UTF_16LE)

        // DWORD handle (unused)
        val handle: MemorySegment = global.allocate(JAVA_INT)

        val size = getFileVersionInfoSizeWHandle.invokeExact(path, handle) as Int
        if (size == 0) return null


        val buffer: MemorySegment = global.allocate(ValueLayout.JAVA_BYTE, size.toLong())

        if (getFileVersionInfoWHandle.invokeExact(path, 0, size, buffer) as Int == 0) return null

        // query language & codepage
        val transPtr: MemorySegment = global.allocate(ADDRESS)
        val transLen: MemorySegment = global.allocate(JAVA_INT)

        if (verQueryValueWHandle.invokeExact(
                buffer,
                global.allocateFrom("\\VarFileInfo\\Translation", Charsets.UTF_16LE),
                transPtr,
                transLen
            ) as Int == 0
        ) return null

        val trans = transPtr.get(ADDRESS, 0)
            .reinterpret(transLen.get(JAVA_INT, 0).toLong())

        val lang: Short = trans.get(JAVA_SHORT, 0)
        val codePage: Short = trans.get(JAVA_SHORT, 2)

        val subBlock = String.format(
            "\\StringFileInfo\\%04x%04x\\FileDescription",
            lang.toInt() and 0xFFFF, codePage.toInt() and 0xFFFF
        )

        val valuePtr: MemorySegment = global.allocate(ADDRESS)
        val valueLen: MemorySegment = global.allocate(JAVA_INT)

        if (verQueryValueWHandle.invokeExact(
                buffer,
                global.allocateFrom(subBlock, Charsets.UTF_16LE),
                valuePtr,
                valueLen
            ) as Int == 0
        ) return null

        val value = valuePtr.get(ADDRESS, 0).reinterpret(valueLen.get(JAVA_INT, 0).toLong())
        return value.getString(0, Charsets.UTF_16LE)
    }

    private data class AppInfo(
        val name: String,
        val title: String,
        val className: String,
        val processPath: Path?,
        val processId: Int,
    ) {
        val processName = processPath?.fileName.toString()

        companion object {
            val NULL = AppInfo("Unknown", "<unknown>", "unknown", null, 0)
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