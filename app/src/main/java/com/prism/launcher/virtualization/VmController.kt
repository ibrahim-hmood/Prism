package com.prism.launcher.virtualization

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.view.Surface
import com.prism.launcher.PrismLogger
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Manages the lifecycle of a virtualized OS.
 *
 * Two backends:
 *  - AVF (Android Virtualization Framework, API 34+): runs PrismOS as a Microdroid pVM.
 *  - QEMU: launches a bundled qemu-system-aarch64 binary for arbitrary ISO images or
 *    as a fallback on devices without AVF hardware support.
 *
 * The VM display is streamed to whatever [Surface] is passed to [start] / [resume].
 * On AVF the surface is wired via VirtualDisplay; on QEMU it connects to an in-process
 * VNC server over localhost.
 */
class VmController(private val context: Context) {

    enum class Mode { PRISM_OS, CUSTOM_ISO }

    enum class State { STOPPED, BOOTING, RUNNING, PAUSED, ERROR }

    companion object {
        private const val TAG = "VmController"
    }

    var state: State = State.STOPPED
        private set(value) {
            field = value
            if (value == State.ERROR) {
                PrismLogger.logError(TAG, "State -> ERROR: ${lastError ?: "(no detail)"}")
            } else {
                PrismLogger.logInfo(TAG, "State -> $value")
            }
            onStateChanged?.invoke(value)
        }

    /** Human-readable detail for the last [State.ERROR] transition — surfaced in the UI so a
     * failed boot says *why* instead of just "Error". Cleared on every fresh [start]. */
    var lastError: String? = null
        private set

    var onStateChanged: ((State) -> Unit)? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VmController")
    }

    // Queued component to launch once the VM reaches RUNNING
    private var pendingApp: ComponentName? = null

    // QEMU process handle (QEMU path only)
    private var qemuProcess: Process? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun start(mode: Mode, isoPath: String? = null, surface: Surface) {
        if (state == State.RUNNING || state == State.BOOTING) {
            PrismLogger.logWarning(TAG, "start($mode) ignored — already $state")
            return
        }
        PrismLogger.logInfo(TAG, "start(mode=$mode, isoPath=$isoPath) — avfSupported=${isAvfSupported()}")
        lastError = null
        state = State.BOOTING
        executor.execute {
            try {
                if (mode == Mode.PRISM_OS && isAvfSupported()) {
                    PrismLogger.logInfo(TAG, "Booting via AVF backend")
                    startAvf(surface)
                } else if (mode == Mode.PRISM_OS) {
                    PrismLogger.logInfo(TAG, "Booting PrismOS via QEMU backend (no AVF support)")
                    startQemu(defaultPrismOsQemuImage(), isIso = false, surface)
                } else {
                    val iso = isoPath ?: throw IllegalStateException("No ISO selected — pick one in Settings > OS Virtualization first.")
                    PrismLogger.logInfo(TAG, "Booting custom ISO via QEMU backend: $iso")
                    startQemu(iso, isIso = true, surface)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "Boot failed", e)
                state = State.ERROR
            }
        }
    }

    fun pause() {
        if (state != State.RUNNING) return
        PrismLogger.logInfo(TAG, "pause()")
        executor.execute {
            try {
                pauseQemu()
                state = State.PAUSED
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "pause() failed", e)
                state = State.ERROR
            }
        }
    }

    fun resume(surface: Surface) {
        if (state != State.PAUSED) return
        PrismLogger.logInfo(TAG, "resume()")
        executor.execute {
            try {
                resumeQemu(surface)
                state = State.RUNNING
                pendingApp?.let { sendAppIntent(it); pendingApp = null }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                PrismLogger.logError(TAG, "resume() failed", e)
                state = State.ERROR
            }
        }
    }

    fun stop() {
        PrismLogger.logInfo(TAG, "stop()")
        executor.execute {
            try {
                qemuProcess?.destroy()
                qemuProcess = null
            } catch (e: Exception) {
                PrismLogger.logWarning(TAG, "stop(): failed to destroy QEMU process — ${e.message}")
            }
            state = State.STOPPED
        }
    }

    /**
     * Sends a request to PrismOS to open [cn].
     * If the VM is still booting the request is queued and replayed once RUNNING.
     */
    fun sendAppIntent(cn: ComponentName) {
        when (state) {
            State.RUNNING -> executor.execute { sendViaAdb(cn) }
            State.BOOTING, State.PAUSED -> {
                PrismLogger.logInfo(TAG, "sendAppIntent(${cn.flattenToShortString()}) queued — VM is $state")
                pendingApp = cn
            }
            else -> PrismLogger.logWarning(TAG, "sendAppIntent(${cn.flattenToShortString()}) dropped — VM is $state")
        }
    }

    fun isAvfSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false // API 34
        return try {
            val svc = context.getSystemService("virtualization_service")
            svc != null
        } catch (_: Exception) {
            false
        }
    }

    // ── AVF backend ───────────────────────────────────────────────────────────

    private fun startAvf(surface: Surface) {
        // Android Virtualization Framework path.
        // Requires android.permission.MANAGE_VIRTUAL_MACHINE and API 34+.
        // The PrismOS Microdroid payload must be present at filesDir/prism_os/vm_config.json.
        //
        // Reflection is used so the code compiles on older SDK targets.
        PrismLogger.logInfo(TAG, "startAvf(): resolving VirtualMachineManager via reflection")
        val vmMgrClass = Class.forName("android.system.virtualmachine.VirtualMachineManager")
        val vmMgr = context.getSystemService(vmMgrClass)
            ?: throw UnsupportedOperationException("VirtualMachineManager unavailable")

        val configDir = File(context.filesDir, PrismOsConfig.PAYLOAD_DIR)
        val configFile = File(configDir, PrismOsConfig.MICRODROID_CONFIG)
        if (!configFile.exists()) throw IllegalStateException("PrismOS image not found at ${configFile.path}")

        val configBuilderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")
        val configBuilder = configBuilderClass.getConstructor(Context::class.java).newInstance(context)
        configBuilderClass.getMethod("setPayloadConfigPath", String::class.java)
            .invoke(configBuilder, configFile.absolutePath)
        configBuilderClass.getMethod("setMemoryBytes", Long::class.javaPrimitiveType)
            .invoke(configBuilder, (PrismOsConfig.DEFAULT_RAM_MB * 1024L * 1024L))
        val config = configBuilderClass.getMethod("build").invoke(configBuilder)

        val vmClass = Class.forName("android.system.virtualmachine.VirtualMachine")
        val getOrCreate = vmMgrClass.getMethod(
            "getOrCreate",
            String::class.java,
            Class.forName("android.system.virtualmachine.VirtualMachineConfig")
        )
        val vm = getOrCreate.invoke(vmMgr, "prism_os", config)

        // Wire the surface as the VM display via VirtualDisplay
        try {
            vmClass.getMethod("setSurface", Surface::class.java).invoke(vm, surface)
        } catch (_: NoSuchMethodException) { /* older AVF build without display API */ }

        val callbackClass = Class.forName("android.system.virtualmachine.VirtualMachineCallback")
        val runMethod = vmClass.getMethod("run")
        runMethod.invoke(vm)

        PrismLogger.logInfo(TAG, "startAvf(): vm.run() returned — AVF VM launched")
        state = State.RUNNING
        pendingApp?.let { sendAppIntent(it); pendingApp = null }
    }

    // ── QEMU backend ──────────────────────────────────────────────────────────

    private fun startQemu(isoPath: String, isIso: Boolean, surface: Surface) {
        val qemuBin = resolveQemuBinary()
            ?: throw IllegalStateException(
                "QEMU binary not found. It must be bundled at " +
                "app/src/main/jniLibs/arm64-v8a/${PrismOsConfig.QEMU_BINARY} (installed into " +
                "${context.applicationInfo.nativeLibraryDir}) — Android won't execute a binary " +
                "placed anywhere else (e.g. assets or filesDir) on this API level."
            )
        PrismLogger.logInfo(TAG, "startQemu(): resolved binary at ${qemuBin.absolutePath}")

        // VNC on localhost:5900 so we can render to the SurfaceView
        val vncPort = 5900
        val cmd = mutableListOf(
            qemuBin.absolutePath,
            "-machine", "virt",
            // "-cpu max" asks the accelerator for every feature it supports, which is a known
            // crash trigger on several mobile/embedded QEMU builds' TCG (software) backend —
            // a fixed, well-tested aarch64 core model is far more conservative and portable
            // across whatever build a user bundles.
            "-cpu", "cortex-a72",
            // A sandboxed app has no access to /dev/kvm (that needs root), so KVM is never a real
            // option here — force TCG explicitly rather than trusting this binary's own
            // accelerator auto-probe, since a build that tries KVM and mishandles the resulting
            // permission failure is a very plausible cause of an instant, silent SIGSEGV.
            "-accel", "tcg",
            "-m", "${PrismOsConfig.DEFAULT_RAM_MB}"
        )
        // An .iso is CD-ROM media (ISO9660 + El Torito boot catalog) — attaching it as a raw
        // virtio block device (as if it were a disk image) skips that boot path entirely on most
        // Linux install media, which is why nothing appeared to boot.
        if (isIso) {
            cmd += listOf("-cdrom", isoPath)
        } else {
            cmd += listOf("-drive", "if=virtio,format=raw,file=$isoPath")
        }
        // The "virt" machine has no boot ROM of its own — without UEFI firmware handing off to
        // the disk's bootloader, QEMU just sits at a black framebuffer forever. Optional because
        // we can't bundle a firmware binary ourselves; if present, use it.
        val firmware = File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/${PrismOsConfig.UEFI_FIRMWARE}")
        if (firmware.exists()) {
            cmd += listOf("-bios", firmware.absolutePath)
            PrismLogger.logInfo(TAG, "startQemu(): using UEFI firmware ${firmware.absolutePath}")
        } else {
            PrismLogger.logWarning(TAG, "startQemu(): no UEFI firmware at ${firmware.absolutePath} — the virt machine has no boot ROM without one, so QEMU may run with nothing to display")
        }
        cmd += listOf(
            "-display", "vnc=127.0.0.1:${vncPort - 5900}",
            "-device", "virtio-gpu-pci",
            "-net", "none"
        )
        // Deliberately no "-nographic" — it disables the video/display devices entirely and
        // conflicts with "-display vnc=...", which is the only thing actually feeding the
        // SurfaceView.

        // Android launches app subprocesses with a near-empty environment — no HOME, no TMPDIR.
        // QEMU is built on glib, which resolves both during early startup (scratch files,
        // locking) via g_get_home_dir()/g_get_tmp_dir(); a build that doesn't defensively handle
        // a null/inaccessible result there is a very plausible cause of a crash this early, before
        // any of QEMU's own logging runs. Point both at real, writable, app-owned directories —
        // this is exactly what Termux's launcher does for you automatically, which is why the
        // same binary and flags can work there and crash here.
        val qemuHome = File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/home").apply { mkdirs() }
        val qemuTmp = File(context.cacheDir, "qemu_tmp").apply { mkdirs() }

        // A ProcessBuilder-spawned binary is a plain execve() through the system linker, not a
        // zygote/app_process fork — it never gets the automatic linker namespace that makes
        // System.loadLibrary() transparently find nativeLibraryDir. The Termux .so's RPATH was
        // also stripped (it pointed at a Termux path that doesn't exist here), so without an
        // explicit LD_LIBRARY_PATH the linker has no way to locate ANY of QEMU's ~30 sibling
        // dependencies, no matter how correctly they're named/renamed.
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        PrismLogger.logInfo(TAG, "startQemu(): launching: ${cmd.joinToString(" ")}")
        val processBuilder = ProcessBuilder(cmd)
            .redirectErrorStream(true)
        processBuilder.environment()["HOME"] = qemuHome.absolutePath
        processBuilder.environment()["TMPDIR"] = qemuTmp.absolutePath
        processBuilder.environment()["LD_LIBRARY_PATH"] = nativeLibDir
        val process = processBuilder.start()
        qemuProcess = process

        // A Process's stdout pipe has a small OS buffer — if nothing ever reads it, QEMU can
        // block on write() and hang forever the moment it logs enough output, which looks
        // identical to "stuck booting" from the UI. Drain it continuously on its own thread,
        // and mirror every line into PrismLogger so QEMU's own diagnostics show up in Diagnostics.
        val output = StringBuilder()
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > 8000) output.delete(0, output.length - 8000)
                    }
                    PrismLogger.logDebug("$TAG-qemu", line)
                }
            } catch (_: Exception) { /* stream closed on process exit */ }
        }, "VmController-qemu-output").apply { isDaemon = true; start() }

        // Give QEMU a moment to start, then confirm it's actually still alive before declaring
        // success — previously this was unconditional, so a QEMU that exited immediately (bad
        // flags, missing shared libs, unsupported CPU) still got reported as RUNNING.
        Thread.sleep(1500)
        if (!process.isAlive) {
            val tail = synchronized(output) { output.toString() }.takeLast(500)
            logCrashDetailsFromLogcat(process.exitValue())
            throw IllegalStateException("QEMU exited immediately (code ${process.exitValue()}): ${tail.ifBlank { "no output" }}")
        }
        PrismLogger.logInfo(TAG, "startQemu(): process alive after 1.5s (pid unknown on this API), connecting VNC on port $vncPort")

        val vncConnected = connectVncToSurface(vncPort, surface)
        if (!vncConnected) {
            PrismLogger.logWarning(TAG, "startQemu(): VNC never connected — QEMU process is alive, but nothing will render to the SurfaceView")
        }

        // Nothing watches for the VM dying *after* this point otherwise — if QEMU crashes a few
        // seconds into boot rather than immediately, the app would just sit on RUNNING forever
        // with a dead process and a frozen/blank surface, no error surfaced anywhere.
        Thread({
            val exitCode = process.waitFor()
            if (qemuProcess === process) {
                PrismLogger.logWarning(TAG, "startQemu(): QEMU process exited (code $exitCode)")
                logCrashDetailsFromLogcat(exitCode)
                if (state == State.RUNNING || state == State.PAUSED) {
                    lastError = "QEMU process terminated unexpectedly (code $exitCode)"
                    state = State.ERROR
                }
            }
        }, "VmController-qemu-watchdog").apply { isDaemon = true; start() }

        state = State.RUNNING
        pendingApp?.let { sendAppIntent(it); pendingApp = null }
    }

    /**
     * When the QEMU subprocess dies from a signal (SIGSEGV etc. — a Unix exit code >= 128 encodes
     * 128+signal), the actual detail behind it can come from two completely different places:
     *
     * 1. A genuine native crash (bad pointer dereference, etc.) is handled by Android's own crash
     *    handler (debuggerd), which writes a full tombstone/backtrace to logcat's dedicated
     *    "crash" buffer.
     * 2. A dynamic-linker failure ("CANNOT LINK EXECUTABLE" — a missing .so dependency) is a
     *    DIFFERENT class of death entirely: it never reaches debuggerd at all, so the crash buffer
     *    is legitimately empty for it. The linker reports it through its own fatal-error path
     *    (tag "linker") into the general logcat buffer instead — this is the class of failure this
     *    exact binary kept hitting (missing libz.so.1, then further missing dependencies), and the
     *    crash-buffer-only check used to report it as empty/found-nothing even though the real
     *    cause was sitting right there in the main buffer the whole time.
     *
     * Neither flows through the process's own stdout/stderr, so the line-by-line capture in
     * [startQemu] can never show either one. Both are readable for the app's own UID without any
     * special permission (the subprocess shares Prism's UID) — check both, best-effort, so a
     * failure's actual cause shows up in Diagnostics automatically either way.
     */
    private fun logCrashDetailsFromLogcat(exitCode: Int) {
        if (exitCode < 128) return // not a signal death — nothing extra to find
        try {
            Thread.sleep(300) // debuggerd/the linker need a moment to finish writing to logcat

            val crashLog = readLogcatBuffer("crash", 200)
            if (crashLog.isNotBlank()) {
                PrismLogger.logError("$TAG-crash", crashLog)
                return
            }

            val mainLog = readLogcatBuffer("main", 300)
            val linkerLines = mainLog.lineSequence()
                .filter { it.contains("linker", ignoreCase = true) }
                .filter { it.contains("CANNOT LINK", ignoreCase = true) || it.contains("not found", ignoreCase = true) }
                .toList()
            if (linkerLines.isNotEmpty()) {
                PrismLogger.logError("$TAG-linker", linkerLines.joinToString("\n"))
            } else {
                PrismLogger.logWarning(TAG, "logCrashDetailsFromLogcat(): found nothing in the crash or main logcat buffers for signal ${exitCode - 128} — may have rotated out of both by the time this ran")
            }
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "logCrashDetailsFromLogcat(): couldn't read logcat — ${e.message}")
        }
    }

    private fun readLogcatBuffer(buffer: String, tailLines: Int): String {
        val logcatProcess = ProcessBuilder("logcat", "-d", "-b", buffer, "-t", "$tailLines")
            .redirectErrorStream(true)
            .start()
        val text = logcatProcess.inputStream.bufferedReader().readText().trim()
        logcatProcess.waitFor()
        return text
    }

    /**
     * Android 10+ mounts app-private storage `noexec` — a binary copied to filesDir can never be
     * exec()'d there regardless of chmod bits. The only guaranteed-executable location is the
     * app's nativeLibraryDir, populated from the lib-prefixed .so files under jniLibs/<abi> at
     * install time. So the QEMU binary must ship as
     * app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so (see [PrismOsConfig.QEMU_BINARY])
     * rather than as an asset.
     */
    private fun resolveQemuBinary(): File? {
        val fromNativeLibDir = File(context.applicationInfo.nativeLibraryDir, PrismOsConfig.QEMU_BINARY)
        if (fromNativeLibDir.exists()) return fromNativeLibDir
        PrismLogger.logWarning(TAG, "resolveQemuBinary(): not found at ${fromNativeLibDir.absolutePath}")
        return null
    }

    private fun pauseQemu() {
        // Send QEMU monitor command via stdin (if monitor pipe is wired) — no-op for now
    }

    private fun resumeQemu(surface: Surface) {
        // Re-connect VNC renderer to new surface after page re-attach
        connectVncToSurface(5900, surface)
    }

    /**
     * Minimal VNC-to-Surface bridge: connects to the QEMU VNC server and pushes
     * frame updates to [surface] via [android.graphics.Canvas].
     * A full implementation would use a VNC client library; this wires up the socket
     * and hands off to [VncSurfaceRenderer].
     *
     * QEMU's VNC server isn't necessarily listening the instant the process starts, so this
     * retries with a short backoff instead of a single silent attempt — the previous version
     * tried once, swallowed any failure, and claimed in a comment that "the renderer will retry
     * on its own thread," which wasn't actually true; nothing retried, so a VNC server that took
     * slightly too long to come up meant no frames ever rendered, with no error anywhere.
     * Returns whether a connection was actually established.
     */
    private fun connectVncToSurface(port: Int, surface: Surface): Boolean {
        val maxAttempts = 6
        repeat(maxAttempts) { attempt ->
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                VncSurfaceRenderer(socket, surface).start()
                PrismLogger.logInfo(TAG, "connectVncToSurface(): connected on attempt ${attempt + 1}")
                return true
            } catch (e: Exception) {
                PrismLogger.logWarning(TAG, "connectVncToSurface(): attempt ${attempt + 1}/$maxAttempts failed — ${e.message}")
                if (attempt < maxAttempts - 1) Thread.sleep(500)
            }
        }
        return false
    }

    // ── ADB intent bridge ─────────────────────────────────────────────────────

    /**
     * Sends an ADB shell command to the PrismOS guest to start [cn] via am start.
     * For AVF guests this uses vsock; for QEMU guests it uses ADB over localhost TCP.
     */
    private fun sendViaAdb(cn: ComponentName) {
        try {
            val adbCmd = "am start -n ${cn.flattenToString()}"
            if (isAvfSupported()) {
                sendVsock(adbCmd)
            } else {
                sendAdbTcp(adbCmd)
            }
        } catch (_: Exception) { /* guest may not be ready yet */ }
    }

    private fun sendVsock(cmd: String) {
        // vsock requires android.system.VsockSocketAddress (API 33+)
        // Reflection used to keep compatibility with older targets
        try {
            val addrClass = Class.forName("android.system.VsockSocketAddress")
            val addr = addrClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(PrismOsConfig.VSOCK_CID, PrismOsConfig.VSOCK_PORT)
            val socket = Class.forName("android.net.LocalSocket")
                .getConstructor().newInstance()
            // Full vsock path requires NDK; placeholder for native implementation
            @Suppress("UNUSED_VARIABLE") val unused = listOf(addr, socket, cmd)
        } catch (_: Exception) { }
    }

    private fun sendAdbTcp(cmd: String) {
        Socket("127.0.0.1", 5555).use { s ->
            s.getOutputStream().write("$cmd\n".toByteArray())
        }
    }

    private fun defaultPrismOsQemuImage(): String =
        File(context.filesDir, "${PrismOsConfig.PAYLOAD_DIR}/prism_os.img").absolutePath
}
