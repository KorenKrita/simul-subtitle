package io.github.korenkrita.simulsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The session on the phone and the desktop, with a fake platform: how it shows captions and how it ends. */
class CaptionSessionTest {
    // the translator: every request gets "译文" as a stream (one request at a time is enough here)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val mtCalls = AtomicInteger()
    /** the system prompts of the translation requests, in order */
    private val systems = java.util.concurrent.CopyOnWriteArrayList<String>()
    /** the Jev requests: "path|authorization|model" */
    private val jevCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    s.use { sock ->
                        val inp = sock.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()) }
                        val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        var o = 0; val body = ByteArray(len)
                        while (o < len) { val n = inp.read(body, o, len - o); if (n < 0) return@use; o += n }
                        val req = Json.parse(String(body)) as? Map<*, *>
                        val path = head.lineSequence().first().split(' ').getOrNull(1).orEmpty()
                        if (path.endsWith("/systemone")) {
                            // Jev: recorded, answered with the same stream (not an answer, so the session goes on without it)
                            val auth = head.lines().firstOrNull { it.lowercase().startsWith("authorization:") }?.substringAfter(':')?.trim()
                            jevCalls += "$path|$auth|${req?.get("model")}"
                        } else {
                            ((req?.get("messages") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("content")?.let { systems += it as String }
                            mtCalls.incrementAndGet()
                        }
                        val sse = listOf("""{"choices":[{"delta":{"content":"译文"}}]}""",
                            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""").joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n$sse".toByteArray()); flush()
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    private val hosts = mutableListOf<FakeHost>()
    @After fun close() { server.close(); hosts.forEach { it.shutdown() } }

    private val logs = File(System.getProperty("java.io.tmpdir"), "simulsub-session-test-${System.nanoTime()}")

    private inner class FakePrefs : SessionPrefs {
        override val jevOn = false
        @Volatile override var jevCheck = false
        override val keepLogs = false
        @Volatile override var keepTranscript = false
        override val keepSentences = CaptionBoard.DEFAULT_SENTENCES
        override val keepChars = CaptionBoard.DEFAULT_CHARS
        override val finalOnly = false
        override val showJa = true
        override val showStats = false
        @Volatile override var scene = Scene.GENERAL
        override val sourceLang: Lang? = Lang.JA
        override val targetLang = Target.ZH
        override val dedupeJoin = false
        override val contextOn = false
        override val contextSentences = 0
        @Volatile override var jevProvider = JevProvider.OFFICIAL
        override fun jevKey(p: JevProvider): String? = null
        @Volatile var custom: Pair<JevCustom, String>? = null
        override fun jevCustomService() = custom
        override fun byokService() = ByokService("http://127.0.0.1:${server.localPort}/v1/chat/completions", "m-1", "")
        // tested before: no probe
        override fun byokVariant(url: String, model: String): String? = ByokTranslator.NONE_WORKED
        override fun setByokVariant(url: String, model: String, name: String?) {}
    }

    /**
     * Audio: [speech] s of a tone between half a second of silence before and two after, then [end] (null: the
     * source never ends by itself and waits for cancel). Read as fast as the session takes it.
     */
    private class FakeAudio(speech: Float, private val end: String?, private val endsByItself: Boolean = true,
                            utterances: Int = 1) : AudioSource {
        private val one = ((0.5f + speech + 2f) * 16_000).toInt()
        private val samples = FloatArray(one * utterances).also { s ->
            for (u in 0 until utterances) for (i in (8_000 until 8_000 + (speech * 16_000).toInt())) s[u * one + i] = 0.3f * kotlin.math.sin(i * 0.2f)
        }
        /** read up to [n] samples; the rest waits until [more] is counted down */
        @Volatile var gateAt = Int.MAX_VALUE
        val more = CountDownLatch(1)
        private var pos = 0
        private val cancelled = CountDownLatch(1)
        @Volatile var closed = false
        private val t0 = monoMs()
        override val estimated = false
        override fun read(frame: FloatArray): Long {
            if (cancelled.count == 0L) return -1
            if (pos + frame.size > samples.size) {
                if (endsByItself) return -1
                cancelled.await(); return -1
            }
            if (pos >= gateAt) more.await()
            System.arraycopy(samples, pos, frame, 0, frame.size); pos += frame.size
            return t0 + pos * 1000L / 16_000
        }
        @Volatile var endAsked = false
        override fun endReason(): String? { endAsked = true; return end }
        override fun close() { closed = true }
        override fun cancel() = cancelled.countDown()
    }

    private inner class FakeHost(val audio: FakeAudio, val asrFails: Boolean = false,
                                 /** a recognition of speech waits for this (not interruptible, as a native call) */
                                 val asrGate: CountDownLatch? = null,
                                 /** opened instead of [audio] */
                                 val source: AudioSource? = null) : SessionHost {
        init { hosts += this }
        private val ui = Executors.newSingleThreadScheduledExecutor()
        fun shutdown() = ui.shutdownNow()
        val inAsr = CountDownLatch(1)
        override val prefs = FakePrefs()
        val released = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var lines: List<CaptionLine> = emptyList()
        @Volatile var diag: String? = null
        override fun post(r: () -> Unit) { ui.execute(r) }
        override fun postDelayed(r: () -> Unit, ms: Long): AutoCloseable {
            val f = ui.schedule(r, ms, TimeUnit.MILLISECONDS)
            return AutoCloseable { f.cancel(false) }
        }
        override fun clock() = monoMs()
        override fun logInfo(m: String) {}
        override fun logWarn(m: String, e: Throwable?) {}
        override val logRoot = logs
        override val appVersion = "test"
        override fun deviceInfo(byokUrl: String?) = mapOf<String, Any?>("device" to "test")
        override val deviceWord = "电脑"
        override val endHint = "点 ✕ 结束"
        override fun loadModels(asrThreads: Int) = object : SpeechModels {
            override val asr = object : Asr {
                override fun recognize(samples: FloatArray): String {
                    // the warm-up is silence; real speech carries the tone
                    if (samples.none { it != 0f }) return ""
                    if (asrFails) error("识别坏了")
                    asrGate?.let { g -> inAsr.countDown(); while (g.count > 0) try { g.await() } catch (_: InterruptedException) {} }
                    return "テスト"
                }
            }
            override fun vadProb(frame: FloatArray) = if (frame.any { kotlin.math.abs(it) > 0.01f }) 0.95f else 0.02f
            override fun release() = released.countDown()
        }
        override fun openAudio(log: SessionLog?) = source ?: audio
        override fun show(lines: List<CaptionLine>, diag: String?, mtLight: ServiceStatus.Light, jevLight: ServiceStatus.Light,
                          mtReason: String?, jevReason: String?) { this.lines = lines; this.diag = diag; diag?.let { diags += it } }
        val diags = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun ended() = ended.countDown()
        override fun finished() = finished.countDown()
        /** waits for what was posted to the UI thread so far */
        fun settle() { ui.submit {}.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun audioThatEndsByItselfIsStillTranslatedAndItsReasonStays() {
        val host = FakeHost(FakeAudio(1.5f, "⚠ 系统声音采集停止了（退出码 1）"))
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.ended.await(20, TimeUnit.SECONDS))
        assertTrue(host.released.await(5, TimeUnit.SECONDS))
        host.settle()
        assertTrue(s.ended)
        // what was heard before the end is on screen as a finished sentence, with why it ended and how to go on
        val last = host.lines.last()
        assertEquals("译文", last.zh); assertEquals("テスト", last.ja); assertTrue(last.isFinal)
        assertTrue(host.diag!!, host.diag!!.startsWith("⚠ 系统声音采集停止了（退出码 1）。点 ✕ 结束后可以重新开始。"))
        assertTrue(host.audio.closed)
        assertEquals(1L, host.finished.count) // the window stays
        // nothing runs any more: it cannot be paused
        host.post { s.setPaused(true) }; host.settle()
        assertFalse(s.paused)
    }

    @Test
    fun audioThatEndsWithNothingToSayClosesTheSession() {
        val host = FakeHost(FakeAudio(1.5f, null))
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        assertEquals(1L, host.ended.count)
    }

    @Test
    fun aCustomJevServiceIsAskedAtItsAddressWithItsModelAndKey() {
        val audio = ScriptedAudio()
        val host = FakeHost(FakeAudio(0f, null), source = audio)
        host.prefs.jevCheck = true; host.prefs.jevProvider = JevProvider.CUSTOM
        host.prefs.custom = JevCustom(JevProtocol.SYSTEMONE, "http://127.0.0.1:${server.localPort}/proxy", "jev-x") to "k-1"
        val s = CaptionSession(host)
        host.post { s.start() }
        audio.push(false, 512); audio.push(true, 1500); audio.push(false, 2000)
        val t0 = monoMs()
        while (jevCalls.isEmpty() && monoMs() - t0 < 10_000) Thread.sleep(20)
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        assertTrue(jevCalls.toString(), jevCalls.isNotEmpty() && jevCalls.all { it == "/proxy/v1/systemone|Bearer k-1|jev-x" })
    }

    @Test
    fun aCustomJevServiceNotFilledInPausesTheJudgeAndSaysWhy() {
        val host = FakeHost(FakeAudio(1.5f, null))
        host.prefs.jevCheck = true; host.prefs.jevProvider = JevProvider.CUSTOM
        host.prefs.custom = JevCustom(JevProtocol.SYSTEMONE, "http://127.0.0.1:${server.localPort}/proxy", "") to ""
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        assertTrue(host.diags.toString(), host.diags.any { it.contains("在线 Decisions 模型辅助已暂停：自定义服务的地址或模型还没填好") })
        assertEquals(emptyList<String>(), jevCalls)
    }

    @Test
    fun aSessionWithNoSentenceLeavesNoText() {
        val host = FakeHost(FakeAudio(0f, null)) // no speech at all: no sentence can come
        host.prefs.keepTranscript = true
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        assertTrue(host.released.await(5, TimeUnit.SECONDS))
        assertEquals(emptyList<File>(), Transcript.list(logs))
    }

    @Test
    fun theTranscriptBeingWrittenIsNotDeleted() {
        val t = Transcript.open(logs)
        t.sentence(1, "テスト", "译文")
        assertFalse(Transcript.delete(t.file)); assertTrue(t.file.isFile)
        t.sentence(1, "again", "again") // a segment is written once
        t.close()
        assertEquals(1, t.file.readText().split("テスト").size - 1); assertFalse(t.file.readText().contains("again"))
        assertTrue(Transcript.delete(t.file)); assertFalse(t.file.exists())
        // a stopped session may still be finishing when the next one opens its own: both stay protected
        val a = Transcript.open(logs); val b = Transcript.open(logs)
        assertFalse(Transcript.delete(a.file)); assertFalse(Transcript.delete(b.file))
        a.sentence(1, "テスト", "译文"); a.close()
        assertFalse(Transcript.delete(b.file)); assertTrue(Transcript.delete(a.file))
        b.close()
    }

    @Test
    fun stoppingEndsTheCaptureAndReleasesEverythingWithoutKeepingTheWindow() {
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false))
        val s = CaptionSession(host)
        host.post { s.start() }
        // the sentence comes through while the audio still runs
        val t0 = monoMs()
        while (host.lines.none { it.isFinal } && monoMs() - t0 < 20_000) Thread.sleep(20)
        assertEquals("译文", host.lines.last { it.isFinal }.zh)
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        assertTrue(host.audio.closed)
        Thread.sleep(200); host.settle()
        assertFalse(s.ended)
        assertEquals(1L, host.ended.count); assertEquals(1L, host.finished.count)
    }

    @Test
    fun aFailedWorkerKeepsTheWindowWithTheError() {
        // the phone used to close its overlay here, so the error was never seen; now both platforms keep it
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false), asrFails = true)
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.ended.await(20, TimeUnit.SECONDS))
        assertTrue(host.released.await(5, TimeUnit.SECONDS))
        host.settle()
        assertTrue(host.diag!!, host.diag!!.startsWith("出错：识别坏了。点 ✕ 结束后可以重新开始。"))
        assertTrue(host.audio.closed)
        assertTrue(host.audio.endAsked) // the desktop logs how sysaudio ended
        assertEquals(1L, host.finished.count)
        assertEquals(0, mtCalls.get())
    }

    @Test
    fun theModelsAreFreedOnlyAfterARecognitionInProgressReturns() {
        val gate = CountDownLatch(1)
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false), asrGate = gate)
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.inAsr.await(20, TimeUnit.SECONDS))
        host.post { s.stop() }
        // the stop is done at once, but nothing native is freed while a worker is still inside it
        Thread.sleep(500)
        assertEquals(1L, host.released.count)
        gate.countDown()
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        assertEquals(1L, host.ended.count); assertEquals(1L, host.finished.count)
    }

    @Test
    fun aSettingChangedWhileRunningTranslatesFromTheNextSentence() {
        val audio = FakeAudio(1.5f, null, endsByItself = false, utterances = 2).apply { gateAt = ((0.5f + 1.5f + 2f) * 16_000).toInt() }
        val host = FakeHost(audio)
        val s = CaptionSession(host)
        host.post { s.start() }
        val t0 = monoMs()
        while (host.lines.none { it.isFinal } && monoMs() - t0 < 20_000) Thread.sleep(20)
        val before = systems.size
        assertTrue(before > 0); assertFalse(systems.any { "VTuber" in it })
        // the home screen switches the scene: the session rebuilds its setup, the second sentence uses it
        host.prefs.scene = Scene.VTUBER
        s.prefChanged("scene")
        Thread.sleep(300) // the rebuild runs off the UI thread
        audio.more.countDown()
        while (host.lines.count { it.isFinal } < 2 && monoMs() - t0 < 20_000) Thread.sleep(20)
        assertEquals(2, host.lines.count { it.isFinal })
        assertTrue(systems.drop(before).isNotEmpty() && systems.drop(before).all { "VTuber" in it })
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
    }

    /** Audio the test hands over frame by frame; a read waits for the next one. */
    private class ScriptedAudio : AudioSource {
        private val q = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        private val end = FloatArray(0)
        private var pos = 0L
        override val estimated = false
        fun push(tone: Boolean, ms: Int) = repeat(ms / 32) { q.put(FloatArray(512) { i -> if (tone) 0.3f * kotlin.math.sin(i * 0.2f) else 0f }) }
        /** waits until the session has taken every frame pushed */
        fun drained() { val t0 = monoMs(); while (q.isNotEmpty() && monoMs() - t0 < 10_000) Thread.sleep(5); Thread.sleep(50) }
        override fun read(frame: FloatArray): Long {
            val f = q.take()
            if (f === end) return -1
            System.arraycopy(f, 0, frame, 0, frame.size); pos += frame.size
            return pos * 1000 / 16_000
        }
        override fun endReason(): String? = null
        override fun close() {}
        override fun cancel() = q.put(end)
    }

    @Test
    fun aPauseOverBeforeTheNextFrameStillEndsTheSentence() {
        // no audio arrives while paused (the read waits): the pause and resume both happen between two frames
        val audio = ScriptedAudio()
        val host = FakeHost(FakeAudio(0f, null), source = audio)
        val s = CaptionSession(host)
        host.post { s.start() }
        audio.push(false, 512); audio.push(true, 800); audio.drained()
        host.post { s.setPaused(true) }; host.post { s.setPaused(false) }; host.settle()
        audio.push(true, 800); audio.push(false, 2000)
        val t0 = monoMs()
        while (host.lines.count { it.isFinal } < 2 && monoMs() - t0 < 10_000) Thread.sleep(20)
        // the speech before the pause and after it are two sentences, not one
        assertEquals(2, host.lines.count { it.isFinal })
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun aPauseFinishesTheSentenceDropsTheAudioAndAResumeGoesOn() {
        val audio = ScriptedAudio()
        val host = FakeHost(FakeAudio(0f, null), source = audio)
        host.prefs.keepTranscript = true
        val s = CaptionSession(host)
        host.post { s.start() }
        fun finals() = host.lines.count { it.isFinal }
        // half a sentence, then pause: it is finished as it stands, without waiting for its end silence
        audio.push(false, 512); audio.push(true, 800); audio.drained()
        host.post { s.setPaused(true) }; host.settle()
        audio.push(false, 32)
        val t0 = monoMs()
        while (finals() < 1 && monoMs() - t0 < 10_000) Thread.sleep(20)
        assertEquals(1, finals()); assertEquals(1, mtCalls.get())
        host.settle(); assertTrue(host.diag.toString(), host.diag!!.startsWith("⏸ 已暂停"))
        // what is said while paused is never heard
        audio.push(true, 1500); audio.push(false, 2000); audio.drained()
        Thread.sleep(300)
        assertEquals(1, finals()); assertEquals(1, mtCalls.get())
        // resumed: the next sentence comes through as before
        host.post { s.setPaused(false) }; host.settle()
        audio.push(false, 512); audio.push(true, 1500); audio.push(false, 2000)
        while (finals() < 2 && monoMs() - t0 < 20_000) Thread.sleep(20)
        assertEquals(2, finals()); assertEquals(2, mtCalls.get())
        host.settle(); assertFalse(host.diag.toString(), host.diag.orEmpty().contains("暂停"))
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        // the text kept: both sentences, each with its time
        val text = Transcript.list(logs).single().readText()
        val body = text.lines().filter { it.isNotBlank() }.drop(1).map { if (Regex("\\d\\d:\\d\\d:\\d\\d").matches(it)) "T" else it }
        assertTrue(text, text.startsWith("同传字幕 ") && text.lines()[0].endsWith(" 开始"))
        assertEquals(text, listOf("T", "テスト", "译文", "T", "テスト", "译文"), body)
    }
}
