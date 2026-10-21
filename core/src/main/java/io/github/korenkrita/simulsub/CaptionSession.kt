package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.ServiceStatus.jevShown
import io.github.korenkrita.simulsub.ServiceStatus.light
import io.github.korenkrita.simulsub.ServiceStatus.reason
import io.github.korenkrita.simulsub.ServiceStatus.serviceWarning
import java.io.File

/** The settings a session reads; the app's and the desktop's Prefs. */
interface SessionPrefs {
    val jevOn: Boolean
    val jevCheck: Boolean
    val keepLogs: Boolean
    /** the finished sentences are kept as plain text ([Transcript]) */
    val keepTranscript: Boolean
    val keepSentences: Int
    val keepChars: Int
    val finalOnly: Boolean
    val showJa: Boolean
    val showStats: Boolean
    val scene: Scene
    val sourceLang: Lang?
    val targetLang: Target
    val dedupeJoin: Boolean
    val contextOn: Boolean
    val contextSentences: Int
    val jevProvider: JevProvider
    fun jevKey(p: JevProvider): String?
    fun byokService(): ByokService?
    fun byokVariant(url: String, model: String): String?
    fun setByokVariant(url: String, model: String, name: String?)
}

/** The speech models a session runs on, released by its session thread when it ends. */
interface SpeechModels {
    val asr: Asr
    /** Silero VAD: the probability that [frame] is speech */
    fun vadProb(frame: FloatArray): Float
    fun release()
}

/** System audio, 16 kHz mono, opened, read and closed by the session thread. */
interface AudioSource {
    /**
     * Fills [frame] (blocking). @return when its last sample was captured (ms, [monoMs]'s clock), or -1 once the
     * audio ended: [cancel], or the source ending by itself ([endReason])
     */
    fun read(frame: FloatArray): Long
    /** the last [read] gave the read time, not the capture time */
    val estimated: Boolean
    /**
     * After the audio ended by itself, or was ended because a worker failed: why, for the user (shown in the first
     * case only), and its end in the log; null when it ended without a reason to show
     */
    fun endReason(): String?
    /** Session thread, after the last read. */
    fun close()
    /** Any thread: the session stops; a [read] in progress returns -1 soon. */
    fun cancel()
}

/** One caption line on screen. */
class CaptionLine(val zh: String, val ja: String?, val isFinal: Boolean, /** the sentence (segment id); -1 = none */ val id: Long = -1)

/** What the platform does for a [CaptionSession]. */
interface SessionHost {
    val prefs: SessionPrefs
    /** runs [r] on the UI thread */
    fun post(r: () -> Unit)
    /** runs [r] on the UI thread after [ms]; closing the result cancels it */
    fun postDelayed(r: () -> Unit, ms: Long): AutoCloseable
    /** the clock service pauses (429) are measured on: on the phone it counts deep sleep, unlike [monoMs] */
    fun clock(): Long
    fun logInfo(m: String)
    fun logWarn(m: String, e: Throwable? = null)
    /** where session logs are kept (SessionLog.dir) */
    val logRoot: File
    val appVersion: String
    /** what the session log's session_start says about this device (and its proxy for [byokUrl]) */
    fun deviceInfo(byokUrl: String?): Map<String, Any?>
    /** "手机" / "电脑", in "⚠ 手机忙不过来" */
    val deviceWord: String
    /** how the user ends captions: "点字幕再点 ✕ 结束" / "点 ✕ 结束" */
    val endHint: String
    /** Session thread: loads the models (slow). */
    fun loadModels(asrThreads: Int): SpeechModels
    /** Session thread: starts the system audio; throws with a message for the user when it cannot. */
    fun openAudio(log: SessionLog?): AudioSource
    /** UI thread: what the caption window shows now. */
    fun show(lines: List<CaptionLine>, diag: String?, mtLight: ServiceStatus.Light, jevLight: ServiceStatus.Light,
             mtReason: String?, jevReason: String?)
    /** The session ended by itself and its captions and message stay until the user ends them. Any thread. */
    fun ended()
    /** The session ended by itself with nothing left to show: close it as the user would. UI thread. */
    fun finished()
}

/**
 * One captioning session, on the phone and the desktop: system audio ([SessionHost.openAudio]) → Silero VAD →
 * [Segmenter] → [Pipeline] → the caption window ([SessionHost.show]).
 * Everything heavy happens on its own threads; the UI thread only renders.
 * Start with [start], end with [stop]; settings changes come in through [prefChanged].
 */
class CaptionSession(private val host: SessionHost) {
    private val main = host

    /**
     * One "session" thread owns every native object (VAD, ASR, MT) and the audio source from creation to release:
     * load → capture loop → stop pipeline → wait for its workers → release. [stop] only flips [stopping]
     * and cancels the audio, so nothing is ever freed while another thread is still inside it.
     */
    @Volatile private var stopping = false
    /** Guards [pipeline] so stop never races the session thread's release. */
    private val cancelLock = Any()
    private var pipeline: Pipeline? = null
    /** A BYOK first-use probe in progress (at start or after "保存" while running), so stop can cancel it. */
    private var probing: ByokTranslator? = null
    /** The running session's settings versions and BYOK probe (shares [cancelLock]); null outside a session. */
    @Volatile private var order: SetupOrder<ByokService, ByokTranslator.ThinkingOff?>? = null
    /** The running session's rebuild trigger; null before its pipeline exists and after it ended. */
    @Volatile private var onSetupChange: (() -> Unit)? = null
    /** the audio source, under [cancelLock]: cancelled by stop, also while it is being opened */
    private var audio: AudioSource? = null

    private val prefs = host.prefs
    private val board by lazy { CaptionBoard(prefs.keepSentences, prefs.keepChars) }
    // main thread only
    private val lastUpdateMs = HashMap<Pair<Long, Int>, Long>()
    @Volatile private var backlog = 0
    @Volatile private var overloaded = false
    /** a pipeline worker failed: its error stays on screen */
    @Volatile private var failed = false
    /**
     * The Jev client of the setup published last; its pause shows without a new request, and a 429 of its service
     * installed by any client (also one of an earlier session, or after the last result) refreshes. Main thread only.
     */
    private var activeJudge: Judge? = null
        set(j) {
            if (j === field) return
            jevWatch?.close()
            // an activation queued before stop and run after it must not watch again
            jevWatch = if (stopping) null else j?.watch { refresh() }
            field = j
        }
    private var jevWatch: AutoCloseable? = null
    /** Jev's light state now ([jevShown]); main thread only. */
    private fun jevNow(): ServiceCall? = jevShown(jevState, activeJudge?.paused(), activeJudge?.lastLimit(), jevFrom)
    /** shows a pause that just ended as such; scheduled by [refresh] for the pause on screen */
    private val pauseOver: () -> Unit = { refresh() }
    private var pauseTimer: AutoCloseable? = null
    /** the translator in use: the service its probe accepted */
    /** Last real result of the translator / Jev in this session (null: not asked yet); main thread only. */
    private var mtState: ServiceCall? = null
    private var jevState: ServiceCall? = null
    /** Jev is used this session (one of its switches on) and which of them; set before the pipeline starts. */
    @Volatile private var jevEndsOn = false
    @Volatile private var jevCheckOn = false
    /**
     * Oldest setup version whose results count for each light: raised when a published setup uses another
     * translator / Jev service, so a slow request to the old one does not light the new one. Main thread only.
     */
    private var mtFrom = 0
    private var jevFrom = 0
    /** this session has a Jev judge: the segmenter asks it at sentence ends */
    @Volatile private var jevActive = false
    @Volatile private var logWarned = false
    /** the finished sentences as text, when kept; written by the pipeline's thread, closed by the session thread */
    @Volatile private var transcript: Transcript? = null
    @Volatile private var transcriptFailed = false

    @Volatile private var status = "正在准备…"
    /** the session ended by itself (overload, error, capture lost): the window stays with its last message */
    @Volatile var ended = false; private set
    /**
     * Paused by the user: the audio keeps coming and is dropped before the VAD, so the models, the log and the window
     * stay and a resume needs no reload. The sentence being spoken when it pauses is finished as it stands.
     */
    @Volatile var paused = false; private set
    /** how many times it was paused: the capture thread sees a pause even if it is over by its next frame */
    private val pauses = java.util.concurrent.atomic.AtomicInteger()

    /** UI thread. */
    fun setPaused(p: Boolean) {
        if (ended || paused == p) return // an ended session has nothing to pause or resume
        if (p) pauses.incrementAndGet()
        paused = p
        refresh()
    }

    /** UI thread. */
    fun start() {
        refresh()
        // fixed at the start: switching it while models load affects the next session, as the switch says
        val keepLogs = prefs.keepLogs; val keepTranscript = prefs.keepTranscript
        Thread({ session(keepLogs, keepTranscript) }, "jpsub-session").start()
    }

    private fun session(keepLogs: Boolean, keepTranscript: Boolean) {
        var models: SpeechModels? = null
        var log: SessionLog? = null
        var keepOverlay = false
        try {
            // Fixed for the whole session (the home screen disables them while running): the two Jev switches.
            // Everything else a sentence is translated with is a [Setup], rebuilt when it changes (below).
            val jevEnds = prefs.jevOn; val jevCheck = prefs.jevCheck
            val wantJev = jevEnds || jevCheck
            // a change from here on is seen by the snapshot below or rebuilt after the start
            val order = SetupOrder<ByokService, ByokTranslator.ThinkingOff?>(cancelLock).also { this.order = it }
            val v0 = order.version()
            val s0 = Snap(prefs, wantJev)
            if (s0.byok == null || s0.byok.model.isEmpty()) {
                setStatus("⚠ LLM API 还没填完整（地址、模型 ID），请到设置「翻译」里填好"); host.logWarn("byok incomplete"); return
            }
            val t0 = monoMs()
            val m = host.loadModels(ASR_THREADS).also { models = it }
            val asr = m.asr
            val t1 = monoMs()
            if (stopping) return
            // warm-up so the first real sentence does not pay page-in / first-run costs (nothing is sent anywhere)
            asr.recognize(FloatArray(16_000))
            val t2 = monoMs()
            host.logInfo("load asr=${t1 - t0}ms warmup=${t2 - t1}ms")
            if (stopping) return
            setStatus("准备好了，开始播放就会出字幕")

            // off by default: a log is written only after the user turned it on in settings
            val sl = if (!keepLogs) null
                else SessionLog(SessionLog.dir(host.logRoot), onFail = { host.logWarn("session log: $it"); logWarned = true; main.post { refresh() } })
            log = sl
            if (keepTranscript) transcript = runCatching { Transcript.open(host.logRoot) }
                .onFailure { host.logWarn("transcript", it); transcriptFailed = true; refresh() }.getOrNull()
            sl?.write(mapOf("ev" to "session_start", "app" to host.appVersion,
                "jev_requested" to jevEnds, "jev_ends" to jevEnds, "jev_check" to jevCheck, "final_only" to prefs.finalOnly,
                "mt_engine" to "byok",
                "load_ms" to (t2 - t0)) + host.deviceInfo(s0.byok?.url) + s0.log())

            /**
             * BYOK probes: a URL + model used for the first time is first probed for the switch that turns thinking off
             * (logged like any MT call). At the start the session waits for it; while running it runs on [probeExec] and
             * the service in use keeps translating until it is done, so other changes are not held up behind it.
             * [order] keeps this session's results, also an inconclusive one (no switch stored, used without one), and
             * tells a wanted probe from one a newer save or the end made pointless.
             */
            val probeExec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "jpsub-probe") }
            /** The BYOK service of the setup published last (session / setup worker thread only). */
            var inUse: ByokService? = null

            /** Runs probe [token] of [t]; false: stopped, closed or overtaken by a newer save (nothing recorded). */
            fun runProbe(t: ByokService, token: Long, system: String, target: Target): Boolean {
                val asking = "第一次用这个模型，正在试怎样关闭思考…"
                val before = status
                val probe = ByokTranslator(t, system, null, maxTokens = mtMaxTokens(target))
                synchronized(cancelLock) {
                    // registered under the lock a newer save or the session end cancels under: neither slips in between
                    if (stopping || !order.current(token)) return false
                    probing = probe
                }
                setStatus(asking)
                try {
                    val steps = mutableListOf<ByokTranslator.Step>()
                    val variant = probe.probe(steps)
                    steps.forEach { st -> sl?.write(mapOf("ev" to "byok_probe", "variant" to st.variant.name, "request" to st.result.request,
                        "http" to st.result.http, "response" to st.result.response, "error" to st.result.error, "usable" to st.result.usable,
                        "thinking" to st.result.thinking, "first_content_ms" to st.result.firstContentMs)) }
                    val none = variant == null && probe.exhausted(steps)
                    // stopped early (service error): no switch is stored, the next session tries again
                    val trouble = if (variant == null && !none)
                        "⚠ 你的翻译服务现在不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}" else null
                    synchronized(cancelLock) {
                        if (probing === probe) probing = null
                        // cancelled by a newer save or the end: a partial result says nothing about this model
                        if (stopping || !order.endProbe(t, token, variant, trouble)) return false
                    }
                    when {
                        variant != null -> prefs.setByokVariant(t.url, t.model, variant.name)
                        none -> prefs.setByokVariant(t.url, t.model, ByokTranslator.NONE_WORKED)
                    }
                    return true
                } finally {
                    synchronized(cancelLock) { if (probing === probe) probing = null }
                    if (status == asking) setStatus(before) // unless something more important replaced it meanwhile
                }
            }

            /** A built setup, the warnings it comes with, the BYOK service it really uses and its log entry. */
            class Built(val setup: Setup, val notice: String, val byok: ByokService, val byokEv: Map<String, Any?>)

            /**
             * The translator for BYOK and the service it uses. [wait]: probe a new service here (the start); otherwise
             * start its probe in the background and keep the service in use (with the new prompt) until it is done.
             * null: stopped, or overtaken by a newer change (whose own rebuild follows).
             */
            fun byok(s: Snap, version: Int, wait: Boolean, notices: MutableList<String>): Triple<ByokTranslator, ByokService, Map<String, Any?>>? {
                val t = s.byok!!
                val system = byokSystem(s.scene, s.source, s.target)
                fun known(k: ByokService): Pair<Boolean, ByokTranslator.ThinkingOff?> {
                    val stored = prefs.byokVariant(k.url, k.model)
                    if (stored != null) return true to ByokTranslator.variant(stored)
                    return if (order.knows(k)) true to order.result(k) else false to null
                }
                // the log names the service really used (never the key): the one saved last may still be in its probe
                fun use(k: ByokService, variant: ByokTranslator.ThinkingOff?) = Triple(
                    ByokTranslator(k, system, variant, maxTokens = mtMaxTokens(s.target)), k,
                    mapOf("variant" to variant?.name, "system" to system, "mt_url" to k.url, "mt_model" to k.model) + k.log())
                var (ready, variant) = known(t)
                if (!ready) {
                    val keep = inUse
                    val token = order.beginProbe(t, version)
                    when {
                        // refused because its probe finished since known() above: use that result
                        token == null && order.knows(t) -> variant = known(t).second
                        wait || keep == null -> {
                            if (token == null || !runProbe(t, token, system, s.target)) return null
                            variant = known(t).second
                        }
                        else -> {
                            if (token != null) {
                                val started = runCatching {
                                    probeExec.execute {
                                        if (runProbe(t, token, system, s.target) && order.changed())
                                            onSetupChange?.invoke() // now build with the tested service
                                    }
                                }.isSuccess
                                if (!started) return null // the session is ending
                            } else if (!order.isProbing(t)) return null // overtaken: the newer change builds again
                            notices += "正在测试新保存的模型，测好之前先用原来的"
                            return use(keep, known(keep).second)
                        }
                    }
                }
                order.trouble(t)?.let { notices += it }
                if (variant == null && prefs.byokVariant(t.url, t.model) == ByokTranslator.NONE_WORKED)
                    notices += "⚠ 关不掉这个模型的思考，翻译可能很慢或经常失败"
                return use(t, variant)
            }
            /** A setup for [s] and the warnings it comes with; null: stopped, or overtaken (the newer change builds). */
            fun make(s: Snap, version: Int, wait: Boolean): Built? {
                val notices = mutableListOf<String>()
                val (mt, used, ev) = byok(s, version, wait, notices) ?: return null
                // one Jev client for both features; no key = both paused until one is entered
                // pauses count deep sleep: one kept across sessions ends at the time the service named
                val judge = s.jevKey?.let {
                    Jev(s.provider, it, source = s.source, target = s.target, clock = host::clock)
                }
                if (wantJev && judge == null) notices += "⚠ 在线 Decisions 模型辅助已暂停：还没填 ${s.provider.label} 的 key"
                return Built(Setup(mt, s.scene, s.source, s.target, judge, s.dedupe, s.contextN, version),
                    notices.joinToString("  ·  "), used, ev)
            }
            /** Log a built setup that is now in use: its BYOK details, and [change] = also as a config_change of [s]. */
            fun logUsed(b: Built, s: Snap, change: Boolean) {
                sl?.write(mapOf("ev" to "byok") + b.byokEv)
                // the service really in use: while a newly saved one is probed that is still the previous one
                if (change) sl?.write(mapOf("ev" to "config_change", "setup" to b.setup.version) + s.log() +
                    b.byokEv.filterKeys { it == "mt_url" || it == "mt_model" })
            }
            // A change during the start probe overtakes it: build again from the newest settings (stop ends the loop).
            // Taken only while its version is still the newest, under the lock every change bumps it under, like a
            // setup published while running; a dropped one is not cancelled (nothing to free).
            var firstSnap = s0
            var v = v0
            var built = make(s0, v0, wait = true)
            while (!stopping && (built == null || !order.publish(v) { true })) {
                v = order.version()
                val s = Snap(prefs, wantJev, firstSnap.byok)
                if (s.byok == null || s.byok.model.isEmpty()) return
                firstSnap = s
                built = make(s, v, wait = true)
            }
            if (stopping || built == null) return
            val first = built.setup
            notice = built.notice
            inUse = built.byok
            jevEndsOn = jevEnds; jevCheckOn = jevCheck
            // the services really in use (BYOK: the one its probe accepted, not a newly saved one still probed)
            var mtId = built.byok; var jevId = firstSnap.jevKey?.let { firstSnap.provider.name + "\n" + it }
            first.judge.let { j -> main.post { activeJudge = j; refresh() } }
            // the settings in use are not the ones session_start logged: say so, like a change while running
            logUsed(built, firstSnap, change = firstSnap !== s0)
            val pl = Pipeline(
                asr, first.mt,
                onCaption = { onCaption(it, sl) },
                onBacklog = { backlog = it; refresh() },
                onOverload = {
                    overloaded = true // capture loop exits, then accepted finals are drained below
                    stopCapture() // also when no audio comes (nothing playing): a read must not wait for it
                    setStatus("⚠ ${host.deviceWord}忙不过来，先停止听新的声音，正在处理已经听到的句子…")
                },
                onFailure = { e ->
                    host.logWarn("pipeline worker failed", e)
                    setStatus("出错：${e.message}。${host.endHint}后可以重新开始。")
                    failed = true
                    stopCapture() // the loop sees the audio end; the window stays with the error until the user stops
                },
                scene = first.scene,
                source = first.source,
                target = first.target,
                judge = first.judge,
                judgeEnds = jevEnds,
                checkTranslations = jevCheck,
                dedupeJoin = first.dedupeJoin,
                previousSentences = first.previousSentences,
                setupVersion = first.version,
                log = { e -> sl?.write(e) },
                drafts = { !prefs.finalOnly }, // "only finished sentences" saves the draft work, not just hides it
                onService = { jev, c ->
                    main.post {
                        // a slow request to a service replaced since says nothing about the current one
                        if (c.setup < if (jev) jevFrom else mtFrom) return@post
                        if (jev) jevState = c else mtState = c
                        refresh()
                    }
                },
            )
            // The segmenter's judged hold and its timings follow the sentence-end switch; while its key is missing the
            // pipeline answers "finished" at once, so a cut happens at the shorter Jev limit.
            jevActive = jevEnds

            // Settings changed while running: one worker rebuilds the setup from a fresh snapshot, so a burst of changes
            // (a slider) makes one or two setups, and setups are published in order. A rebuild that a newer change
            // overtook is dropped; the newer one's own task builds again.
            var published = first.version
            val rebuild = Runnable {
                try {
                    val v = order.version()
                    if (v == published || stopping) return@Runnable
                    val s = Snap(prefs, wantJev, inUse)
                    // BYOK is only switched by "保存", which checks the fields; a half-filled one keeps the current setup
                    if (s.byok == null || s.byok.model.isEmpty()) return@Runnable
                    val b = make(s, v, wait = false) ?: return@Runnable
                    // the version check and the publication under the lock every change bumps the version under: a newer
                    // change or the session end either comes before (this setup is dropped) or after (it rebuilds again)
                    // another translator or Jev service: its light starts unknown (grey), never the old one's. The
                    // switch is queued on the main thread before the new setup can produce any result
                    val m = b.byok; val j = s.jevKey?.let { s.provider.name + "\n" + it }
                    val mtNew = m != mtId; val jevNew = j != jevId
                    val judge = b.setup.judge
                    val activate = Runnable {
                        activeJudge = judge
                        if (mtNew) { mtFrom = v; mtState = null }
                        if (jevNew) { jevFrom = v; jevState = null }
                        refresh()
                    }
                    if (!order.publish(v) { pl.setSetup(b.setup) { main.post { activate.run() } } }) return@Runnable
                    mtId = m; jevId = j
                    published = v
                    inUse = b.byok
                    notice = b.notice
                    logUsed(b, s, change = true)
                    refresh()
                } catch (t: Throwable) {
                    // a failed rebuild keeps the current settings; it must not end the session
                    host.logWarn("setup rebuild failed", t)
                    notice = "⚠ 新的设置没能生效：${t.message}"; refresh()
                }
            }
            synchronized(cancelLock) { pipeline = pl }
            if (stopping) return // onDestroy ran before the pipeline was visible to it
            pl.start()
            // after start: setSetup refuses a pipeline that is not running
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "jpsub-setup") }
            onSetupChange = { runCatching { exec.execute(rebuild) } } // rejected once the session ends
            exec.execute(rebuild) // a change made since the first snapshot only bumped the version
            try {
                val lost = capture(pl, m, sl)
                if (failed && !stopping) keepOverlay = true
                else if (lost != null && !stopping) {
                    // what was already heard is still translated and shown, with the reason the capture ended
                    pl.drainFinals(DRAIN_TIMEOUT_MS)
                    // (a worker that failed during the drain keeps its own message)
                    if (!stopping && !failed) setStatus("$lost。${host.endHint}后可以重新开始。")
                    keepOverlay = true
                }
                if (overloaded && !stopping) {
                    // Drain is bounded; afterwards it waits for in-progress native calls to return (cancelled, but
                    // not interruptible), so the count is final and nothing is delivered later.
                    val left = pl.drainFinals(DRAIN_TIMEOUT_MS)
                    val dev = host.deviceWord
                    if (!stopping && !failed) setStatus(if (left == 0) "⚠ ${dev}处理跟不上，已停止采集；已收到的句子都已翻译。${host.endHint}。"
                                                        else "⚠ ${dev}处理跟不上，已停止采集；还有 $left 句未翻译被放弃。${host.endHint}。")
                    keepOverlay = true // models are released below; the captions stay until the user stops
                }
            } finally {
                // No new setup or probe from here on (closed under the lock both check), then end the rebuild worker:
                // it is either in a probe (cancelled now, returns within the request's cancel) or in quick local work,
                // so the wait ends; only then the log is closed and the models are freed below.
                onSetupChange = null
                synchronized(cancelLock) { order.close(); probing?.cancel() }
                exec.shutdownNow(); probeExec.shutdownNow()
                while (!exec.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) ||
                    !probeExec.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) host.logWarn("waiting for the setup worker")
                pl.stop()
                pl.awaitStopped() // ASR/MT workers are out of native code after this
            }
        } catch (e: Throwable) {
            host.logWarn("session failed", e)
            setStatus("出错：${e.message}。${host.endHint}后可以重新开始。")
            keepOverlay = true
        } finally {
            // the pipeline workers have exited (or never started), so this is the last event
            log?.let { it.write(mapOf("ev" to "session_end", "overloaded" to overloaded)); it.close() } // failure → onFail
            // a failure stays shown on the window an ended session keeps
            transcript?.let { it.close(); if (it.failed) transcriptFailed = true }; transcript = null
            // Detach the pipeline under the same lock stop uses; the native free happens outside it.
            synchronized(cancelLock) { pipeline = null }
            models?.release()
            host.logInfo("session released")
            // the captions and the reason stay until the user ends them (✕, the menu bar or the home screen)
            // (a stop that came during the drain or the teardown ends it as the user wanted)
            if (keepOverlay && !stopping) { ended = true; paused = false; host.ended() }
            else if (!keepOverlay && !stopping) main.post { if (!stopping) host.finished() }
        }
    }

    /**
     * Blocking capture loop on the session thread: reads the audio until stop (which cancels it, so the read ends),
     * overload, or the audio ending by itself. @return why it ended by itself, for the user (null: stopped, overloaded,
     * failed, or no reason to show)
     */
    private fun capture(pl: Pipeline, models: SpeechModels, log: SessionLog?): String? {
        // opened outside the lock: starting the audio may take a while, and stop must never wait for it
        if (stopping) return null
        val src = host.openAudio(log)
        synchronized(cancelLock) {
            if (stopping) { src.close(); return null } // stop came while it opened: it found nothing to cancel
            audio = src
        }
        try {
            // with Jev the forced cut asks first and an "unfinished" head may grow to twice this (10 s); without Jev it
            // cuts at the default 7 s. The live log showed long waits were utterances that reached the forced cut.
            // An utterance that starts while Jev is paused (its key is missing) is cut as without Jev.
            val seg = Segmenter({ pl.submit(it) }, minFinalMs = MIN_FINAL_MS, maxHoldSilenceMs = 1_500,
                maxUtteranceMs = if (jevActive) JEV_MAX_UTTERANCE_MS else PLAIN_MAX_UTTERANCE_MS,
                hintWaitMs = if (jevActive) JEV_WAIT_MS else 0, holdHint = pl::holdHint,
                judged = pl::judged, plainMaxUtteranceMs = PLAIN_MAX_UTTERANCE_MS,
                askSilenceMs = JEV_ASK_SILENCE_MS,
                earlyCutAfterMs = EARLY_CUT_AFTER_MS, earlyCutSilenceMs = EARLY_CUT_SILENCE_MS)
            val frame = FloatArray(seg.frameSize)
            var framesRead = 0L
            var statSq = 0.0; var statN = 0; var statMaxP = 0f
            var first = true
            var wasPaused = false
            var pausesSeen = 0
            while (!stopping && !overloaded && !failed) {
                val capturedAt = src.read(frame)
                if (capturedAt < 0) break // stopped, or the audio ended by itself
                if (first) { first = false; setStatus("") } // audio arrives: the capture is running
                // counted, not only read: a pause and resume between two frames (a read may wait) still ends the sentence
                val n = pauses.get()
                if (n != pausesSeen) { pausesSeen = n; seg.flush(); log?.write(mapOf("ev" to "pause")); wasPaused = true }
                if (paused != wasPaused) { wasPaused = paused; log?.write(mapOf("ev" to if (wasPaused) "pause" else "resume")) }
                if (wasPaused) continue
                val f = frame.copyOf()
                framesRead += f.size
                val prob = models.vadProb(f)
                val hal = !src.estimated
                seg.accept(f, prob, capturedAt, estimated = !hal)
                // once every ~5 s: proves audio arrives and what the VAD makes of it (silence vs. no capture)
                var e = 0.0; for (x in f) e += x * x
                statSq += e; statN += f.size; if (prob > statMaxP) statMaxP = prob
                if (framesRead % (16_000 * 5) < f.size) {
                    val rms = Math.sqrt(statSq / statN.coerceAtLeast(1))
                    host.logInfo("capture: frames=$framesRead rms=${"%.4f".format(rms)} vadMax=${"%.2f".format(statMaxP)} hal=$hal")
                    log?.write(mapOf("ev" to "capture", "samples" to framesRead, "rms" to rms, "vad_max" to statMaxP.toDouble(), "hal" to hal))
                    statSq = 0.0; statN = 0; statMaxP = 0f
                }
            }
            if (stopping || overloaded) return null
            // a worker failed: the audio was ended for it; its end goes to the log (the message is the worker's)
            if (failed) { src.endReason(); return null }
            seg.flush()
            return src.endReason()
        } finally {
            synchronized(cancelLock) { audio = null }
            src.close()
        }
    }

    private fun onCaption(c: Caption, log: SessionLog?) {
        // a failed translation is kept as shown ("[翻译失败] …"); a segment with nothing to show is not a sentence
        if (c.isFinal && c.zh.isNotEmpty()) transcript?.sentence(c.segmentId, c.ja, c.zh)
        val t = c.timing
        // timings only: the text of what was said stays out of logcat (it goes to the session log when that is on)
        host.logInfo("seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} audio=${t.audioMs} wait=${t.segmentWaitMs} " +
            "asrQ=${t.asrQueueMs} asr=${t.asrMs} mtQ=${t.mtQueueMs} mt=${t.mtMs} mtDone=${t.mtDoneMs} ja=${c.ja.length} zh=${c.zh.length}")
        log?.write(mapOf("ev" to "caption", "seg" to c.segmentId, "rev" to c.revision, "source_rev" to c.sourceRevision,
            "final" to c.isFinal, "ja" to c.ja,
            "zh" to c.zh, "audio_ms" to t.audioMs, "wait_ms" to t.segmentWaitMs, "asr_q_ms" to t.asrQueueMs, "asr_ms" to t.asrMs,
            "mt_q_ms" to t.mtQueueMs, "mt_ms" to t.mtMs, "check_ms" to t.checkMs, "done_ms" to t.mtDoneMs, "hal" to !c.timeEstimated))
        if (board.accept(c)) refresh()
    }

    private fun setStatus(s: String) { status = s; refresh() }
    /** A warning that stays for the whole session (the status line is cleared once capture starts). */
    @Volatile private var notice = ""

    /** Shows the captions and the state now; any thread. */
    fun refresh() = main.post {
        if (stopping) return@post // a stopped session's late events never touch the window
        // "only finished sentences": drafts are hidden (and a draft already on screen disappears), read live from prefs
        val shown = board.snapshot().let { s -> if (prefs.finalOnly) s.filter { it.isFinal } else s }
        val showJa = prefs.showJa
        val lines = shown.map { CaptionLine(it.zh, if (showJa) it.ja else null, it.isFinal, it.segmentId) }
        // status messages (loading, errors, overload) always show; timings only when enabled in settings
        val diag = buildString {
            if (status.isNotEmpty()) append(status)
            // with no caption yet the placeholder line says it already
            if (paused && status.isEmpty() && lines.isNotEmpty()) append("⏸ 已暂停")
            if (notice.isNotEmpty()) append("${if (isNotEmpty()) "  ·  " else ""}$notice")
            if (logWarned) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 日志写入失败，本次日志不完整")
            if (transcriptFailed || transcript?.failed == true) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 字幕文本保存失败，本次文本不完整")
            // a failing service stays shown, also with timings off; one that works says nothing here
            serviceWarning(mtState, jevNow(), jevEndsOn, jevCheckOn, host.clock(), System.currentTimeMillis())?.let {
                append("${if (isNotEmpty()) "  ·  " else ""}$it")
            }
            // an operational status (stopped, error) must never be pushed out of the capped area by timings
            if (prefs.showStats && status.isEmpty()) shown.lastOrNull()?.let { c ->
                val t = c.timing
                if (isNotEmpty()) append("  ·  ")
                append("识别 ${t.asrMs}ms · 翻译 ${t.mtMs}ms")
                // capture → this text update, for the caption actually rendered now ("~": read time, not capture time)
                lastUpdateMs[c.segmentId to c.revision]?.let { ms ->
                    append(" · 延迟 ${if (c.timeEstimated) "~" else ""}${"%.1f".format(ms / 1000.0)}s")
                }
            }
            if (backlog >= 2 && !overloaded) append("${if (isNotEmpty()) "  ·  " else ""}⚠ 处理跟不上（积压 $backlog 句）")
        }
        val jevNow = jevNow()
        val jevOn = jevEndsOn || jevCheckOn
        val clock = host.clock(); val wall = System.currentTimeMillis()
        host.show(if (lines.isEmpty() && status.isEmpty()) listOf(CaptionLine(if (paused) "已暂停" else "正在听…", null, false)) else lines,
            diag.ifEmpty { null }, light(mtState, true), light(jevNow, jevOn),
            mtState?.let { reason(it, false, clock, wall) },
            if (jevOn) jevNow?.let { reason(it, true, clock, wall) } else "未开启")
        // a pause on screen says "下一句重试" once it is over: show that then, without a request
        pauseTimer?.close(); pauseTimer = null
        jevNow?.limit?.untilMs?.takeIf { it != Long.MAX_VALUE }?.let { until ->
            val left = until - clock
            if (left > 0) pauseTimer = main.postDelayed(pauseOver, left + 50)
        }
        // Record capture → text-update latency once per (segment, revision), right after it was set.
        val now = monoMs()
        for (c in shown) {
            val key = c.segmentId to c.revision
            if (key !in lastUpdateMs && c.audioEndAt > 0) {
                lastUpdateMs[key] = now - c.audioEndAt
                host.logInfo("shown seg=${c.segmentId}.${c.revision}${if (c.isFinal) "F" else "d"} update=${now - c.audioEndAt}ms hal=${!c.timeEstimated}")
            }
        }
        if (lastUpdateMs.size > 64) lastUpdateMs.keys.removeAll { k -> shown.none { it.segmentId to it.revision == k } }
    }

    /**
     * A setting changed while subtitles run; the look itself is the window's. Called with every changed key, from
     * the thread the platform tells it on.
     */
    fun prefChanged(key: String) {
        when (key) {
            // a newer BYOK save makes an unfinished probe of the previous one pointless (and billed): stop it
            "byokSaved" -> {
                synchronized(cancelLock) { order?.byokSaved(); probing?.cancel() }
                onSetupChange?.invoke()
            }
            "showJa", "showStats", "finalOnly" -> refresh()
            "keepSentences", "keepChars" -> main.post { if (board.setLimits(prefs.keepSentences, prefs.keepChars)) refresh() }
            // translation settings: the session rebuilds its setup off the UI thread, from the next sentence on
            in setupKeys -> { order?.changed(); onSetupChange?.invoke() }
            else -> if (key.startsWith("jevKey:")) { order?.changed(); onSetupChange?.invoke() }
        }
    }

    /** Cancels the audio (the capture loop sees it end). Any thread. */
    private fun stopCapture() { synchronized(cancelLock) { audio?.cancel() } }

    /**
     * Never blocks and never frees native objects: the session thread notices [stopping], finishes, waits for the
     * pipeline workers and releases everything itself. UI thread.
     */
    fun stop() {
        stopping = true
        pauseTimer?.close(); pauseTimer = null
        activeJudge = null // stops watching its service
        // Only flags and request cancels (the session thread detaches the pipeline under the same lock before
        // freeing), and the audio's cancel, which the capture loop sees as its end.
        synchronized(cancelLock) { probing?.cancel(); pipeline?.stop(); audio?.cancel() }
    }

    /**
     * The settings a [Setup] is built from, read once so a setup never mixes two states of the preferences.
     * [log] leaves out the keys.
     */
    private class Snap(prefs: SessionPrefs, wantJev: Boolean, fallback: ByokService? = null) {
        val scene = prefs.scene
        val source = prefs.sourceLang
        val target = prefs.targetLang
        val dedupe = prefs.dedupeJoin
        val contextN = if (prefs.contextOn) prefs.contextSentences else 0
        // the BYOK fields from one read; an unfilled profile in settings keeps the service already in use
        val byok = prefs.byokService()?.takeIf { it.model.isNotEmpty() } ?: fallback
        val provider = prefs.jevProvider
        /** null: no Jev wanted, or its key is missing; "" for a provider without a key */
        val jevKey = if (!wantJev) null else if (provider.needsKey) prefs.jevKey(provider) else ""

        fun log() = mapOf("scene" to scene.label, "source_lang" to (source?.code ?: "auto"), "target_lang" to target.stored,
            "jev" to jevKey?.let { provider.name }, "jev_model" to jevKey?.let { provider.model },
            "dedupe_join" to dedupe, "context_sentences" to contextN,
            "mt_model" to byok?.model, "mt_url" to byok?.url)
    }

    companion object {
        /**
         * Settings a running session takes from the next sentence. BYOK changes as one: "保存" writes its fields and
         * then bumps byokSaved, so a half-saved address or model never becomes a setup. jevKey:* is matched apart.
         */
        private val setupKeys = setOf("scene", "scenes", "contextOn", "contextSentences", "dedupeJoin", "jevProvider",
            "sourceLang", "targetLang")
        // Apple silicon: four of the performance cores (M4 Pro: a 7 s recognition 53 -> 44 ms against 2 threads; 6 gave
        // 41 ms), and four threads on a Windows PC too. 8 Gen 3 (1×X4 + 5×A720 + 2×A520): four threads fit on the big
        // cores; not measured on a phone.
        private const val ASR_THREADS = 4
        private const val DRAIN_TIMEOUT_MS = 60_000L
        /** How long after a sentence-end candidate to wait for Jev before finalizing (ASR ~0.1 s + Jev p90 ~0.7 s). */
        private const val JEV_WAIT_MS = 1_000
        /** Jev on: a pause is recognized and asked about after this much silence, cut at 400 ms as before. Replayed
         *  15 min clip: final wait p50 673 -> 544 ms, Jev calls 240 -> 307 (pauses the speaker talked through). */
        private const val JEV_ASK_SILENCE_MS = 224
        private const val JEV_MAX_UTTERANCE_MS = 5_000
        private const val PLAIN_MAX_UTTERANCE_MS = 7_000
        /**
         * Jev off: an utterance with 3 s of audio ends at a 192 ms pause (see Segmenter.earlyCutAfterMs). On a 15 min
         * clip a quarter of the finals came from the 7 s forced cut, which goes back to a pause seconds after it was
         * spoken (one final 4.6 s late): final p90 2610 -> 681 ms, first translation seen p90 1638 -> 644 ms, finals
         * 199 -> 210, the share ending on a particle such as て/けど/から unchanged (25%). A cut at 3.5 s instead got
         * p90 1657 ms with 298 finals; 256 ms pauses p90 1588 ms.
         */
        private const val EARLY_CUT_AFTER_MS = 3_000
        private const val EARLY_CUT_SILENCE_MS = 192
        /**
         * Below this many ms of speech a cut waits for [Segmenter.maxHoldSilenceMs] before it is sent (see
         * Segmenter.holding), so short utterances ("はい", "えっ") used to sit ~1.1 s longer than everything else:
         * a 15 min clip showed a median 1408 ms wait on those against 321 ms on the rest, and the same clip after
         * this change 617 ms end to end (was 1686 ms), at the cost of ~1% of the recognized text differing.
         * 600 has no effect: those utterances carry 400-600 ms of speech, so they still hit the hold.
         */
        private const val MIN_FINAL_MS = 400
    }
}
