package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableStateOf
import java.io.File

/** What the caption window shows; written on the UI thread, read by Compose. */
class OverlayState {
    val lines = mutableStateOf<List<CaptionLine>>(emptyList())
    val diag = mutableStateOf<String?>(null)
    val mtLight = mutableStateOf(ServiceStatus.Light.GREY)
    val jevLight = mutableStateOf(ServiceStatus.Light.GREY)
    val mtReason = mutableStateOf<String?>(null)
    val jevReason = mutableStateOf<String?>(null)
}

/**
 * One captioning session on the Mac / Windows ([CaptionSession] does the work): system audio from tools/mac-capture's
 * or win-capture's sysaudio (16 kHz mono f32 LE on its stdout), the caption window's state ([overlay]).
 */
class CaptionService private constructor(private val sysaudio: File) : SessionHost {
    val overlay = OverlayState()
    // before the session, which reads it when it is made
    override val prefs: Prefs = Prefs.prefs
    private val session = CaptionSession(this)
    /** the session ended by itself (overload, error, capture lost): the window stays with its last message */
    val ended: Boolean get() = session.ended
    /** the sysaudio child of the audio open now; any thread */
    @Volatile private var child: Process? = null

    override fun post(r: () -> Unit) = java.awt.EventQueue.invokeLater(r)
    override fun postDelayed(r: () -> Unit, ms: Long): AutoCloseable {
        val t = javax.swing.Timer(ms.toInt().coerceAtLeast(0)) { r() }.apply { isRepeats = false; start() }
        return AutoCloseable { t.stop() }
    }
    override fun clock() = monoMs()
    override fun logInfo(m: String) = System.err.println("I/$TAG: $m")
    override fun logWarn(m: String, e: Throwable?) { System.err.println("W/$TAG: $m"); e?.printStackTrace() }
    override val logRoot: File get() = dataDir
    override val appVersion: String get() = APP_VERSION
    override fun deviceInfo(byokUrl: String?): Map<String, Any?> = mapOf(
        "proxy" to byokUrl?.let { runCatching { java.net.ProxySelector.getDefault().select(java.net.URI(it)).toString() }.getOrNull() },
        "device" to "${if (isWindows) "Windows" else "Mac"} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
    override val deviceWord = "电脑"
    override val endHint = "点 ✕ 结束"

    // the models ship inside the app (or come from ./models when run from the repo)
    override fun loadModels(asrThreads: Int): SpeechModels {
        val vad = SileroProb(modelsDir)
        val asr = runCatching { SherpaAsr(modelsDir, asrThreads) }.getOrElse { vad.release(); throw it }
        return object : SpeechModels {
            override val asr: Asr = asr
            override fun vadProb(frame: FloatArray) = vad.prob(frame)
            override fun release() { asr.release(); vad.release() }
        }
    }

    override fun openAudio(log: SessionLog?): AudioSource {
        // the shutdown hook ran: no new child (see killCapture)
        if (quitting) return object : AudioSource {
            override fun read(frame: FloatArray) = -1L
            override val estimated = true
            override fun endReason(): String? = null
            override fun close() {}
            override fun cancel() {}
        }
        // launched and published under the hook's lock: the hook either ends this child or this sees it ran
        val p = synchronized(launchLock) {
            if (quitting) null else ProcessBuilder(sysaudio.path).redirectError(ProcessBuilder.Redirect.PIPE).start().also { child = it }
        } ?: return openAudio(log) // quitting now: the source that ends at once
        // its messages (permission refused, no tap) explain an early exit: keep the last ones
        val errTail = java.util.concurrent.ConcurrentLinkedDeque<String>()
        Thread({
            p.errorStream.bufferedReader().forEachLine { l ->
                System.err.println("sysaudio: $l"); errTail.addLast(l); while (errTail.size > 5) errTail.pollFirst()
                // which device it records and how much sound arrives: the log shows it while the session runs
                log?.write(mapOf("ev" to "sysaudio", "line" to l))
            }
        }, "jpsub-sysaudio-err").apply { isDaemon = true }.start()
        val input = java.io.DataInputStream(java.io.BufferedInputStream(p.inputStream))
        return object : AudioSource {
            private var bytes = ByteArray(0)
            override fun read(frame: FloatArray): Long {
                if (bytes.size != frame.size * 4) bytes = ByteArray(frame.size * 4)
                try { input.readFully(bytes) } catch (_: java.io.IOException) { return -1 } // EOF: the child ended or was ended
                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in frame.indices) frame[i] = bb.getFloat()
                // read time: the pipe buffers little (sysaudio writes every 10 ms), so this is close to capture time
                return monoMs()
            }
            override val estimated = true
            override fun endReason(): String {
                val code = p.waitFor()
                log?.write(mapOf("ev" to "capture_end", "exit" to code, "stderr" to errTail.joinToString("\n")))
                return "⚠ 系统声音采集停止了（退出码 $code）" + (errTail.lastOrNull()?.let { "：$it" } ?: "")
            }
            override fun close() { child = null; p.destroy() }
            override fun cancel() = p.destroy() // the reader sees EOF
        }
    }

    override fun show(lines: List<CaptionLine>, diag: String?, mtLight: ServiceStatus.Light, jevLight: ServiceStatus.Light,
                      mtReason: String?, jevReason: String?) {
        overlay.lines.value = lines; overlay.diag.value = diag
        overlay.mtLight.value = mtLight; overlay.jevLight.value = jevLight
        overlay.mtReason.value = mtReason; overlay.jevReason.value = jevReason
    }
    override fun ended() = post { if (current === this) endedState.value = true }
    override fun finished() { if (current === this) stop() }

    /** Settings changed in the main window while subtitles run; the look itself is read by the window directly. */
    private val prefListener: (String) -> Unit = { session.prefChanged(it) }

    companion object {
        private const val TAG = "jpsub"

        /** The session on screen (running, or ended with its message still shown); UI thread. */
        @Volatile var current: CaptionService? = null; private set
        /** set by the shutdown hook: no session starts sysaudio after it */
        @Volatile private var quitting = false
        /** guards the launch of a sysaudio child against [killCapture] */
        private val launchLock = Any()
        /**
         * shutdown hook: ends the sysaudio child of the session on screen; any thread. [quitting] is set and the child
         * read under the lock a launch holds, so a launch either happened before (and is ended here) or sees it.
         */
        fun killCapture() = synchronized(launchLock) { quitting = true; current?.child?.destroy() }
        /** Compose mirrors of [current] != null and of [ended], for the home screen and the menu bar */
        val runningState = mutableStateOf(false)
        val endedState = mutableStateOf(false)
        /** the session on screen is paused (see [CaptionSession.paused]) */
        val pausedState = mutableStateOf(false)
        val running: Boolean get() = current != null

        /** UI thread. A session that ended by itself (its message still shown) is replaced. */
        fun start(sysaudio: File) {
            current?.let { if (it.ended) stop() else return }
            val s = CaptionService(sysaudio)
            current = s; runningState.value = true; endedState.value = false; pausedState.value = false
            s.prefs.listen(s.prefListener)
            s.session.start()
        }

        /** UI thread. */
        fun togglePause() {
            val s = current ?: return
            s.session.setPaused(!s.session.paused); pausedState.value = s.session.paused
        }
        /** UI thread. */
        fun stop() {
            val s = current ?: return
            current = null; runningState.value = false; endedState.value = false; pausedState.value = false
            s.prefs.unlisten(s.prefListener)
            s.session.stop()
        }
    }
}
