package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PipelineTest {
    /** The "audio" encodes its text as sample count, so the test can tell jobs apart. */
    private class FakeAsr(val delayMs: Long = 0) : Asr {
        override fun recognize(samples: FloatArray): String {
            Thread.sleep(delayMs)
            return "ja${samples.size}"
        }
    }

    /** Busy-waits (ignores interrupts like native code) unless cancelled. */
    private class FakeMt(val delayMs: Long) : Mt {
        val cancelled = AtomicBoolean(false)
        val inside = AtomicInteger(0)
        val calls = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            inside.incrementAndGet()
            try {
                calls.add(prompt)
                val end = System.nanoTime() + delayMs * 1_000_000
                while (System.nanoTime() < end) if (cancelled.get()) return null
                return MtResult("zh(${source(prompt)})", 'e', 1, 1)
            } finally { inside.decrementAndGet() }
        }
        override fun cancel() = cancelled.set(true)
    }

    /** Blocks every call until [release]; records the order of the source texts. */
    private class GateMt : Mt {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            order.add(source(prompt)); entered.countDown(); gate.await()
            return MtResult("zh(${source(prompt)})", 'e', 1, 1)
        }
        override fun cancel() = gate.countDown()
    }

    private class CountingAsr : Asr {
        val seen = Collections.synchronizedList(ArrayList<Int>())
        override fun recognize(samples: FloatArray): String { seen.add(samples.size); return "ja${samples.size}" }
    }

    /** Blocks the first recognize() until [gate] opens. */
    private class GateAsr : Asr {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val seen = Collections.synchronizedList(ArrayList<Int>())
        override fun recognize(samples: FloatArray): String {
            if (entered.count > 0) { entered.countDown(); gate.await() }
            seen.add(samples.size); return "ja${samples.size}"
        }
    }

    companion object {
        /** The sentence inside a BYOK user message ([byokUser]). */
        fun source(prompt: String) = prompt.substringAfter("【当前句】\n").substringBefore("\n\n")
        /** The context part of a BYOK user message for these sentences, oldest first. */
        fun context(vararg s: String) = "【前文】\n" + s.joinToString("\n") + "\n\n"
    }

    private fun job(seg: Long, rev: Int, final: Boolean, n: Int) = AsrJob(seg, rev, final, FloatArray(n), monoMs())

    @Test
    fun finalsAreNeverDroppedAndStayInOrder() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(5)
        val p = Pipeline(FakeAsr(), FakeMt(30), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        for (seg in 1L..5L) {
            for (rev in 1..5) p.submit(job(seg, rev, false, rev))
            p.submit(job(seg, 6, true, 100 + seg.toInt()))
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), out.filter { it.isFinal }.map { it.segmentId })
    }

    /** Drafts every 700 ms, MT takes 900 ms: a draft must still reach the screen while the speaker talks. */
    @Test
    fun slowMtStillShowsDraftsBeforeTheFinal() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val mt = FakeMt(900)
        // each draft is long enough to pass the growth gate (Pipeline.DRAFT_MIN_NEW_CHARS): a recognition that only
        // grew by a couple of characters would rightly not be translated, and this test is about the queue
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                1 -> "今日はゲーム配信を始めます"
                2 -> "よろしくお願いしますみなさんこんばんは"
                3 -> "コメントありがとうございますうれしいです"
                4 -> "スパチャありがとうございますだいすきです"
                5 -> "また明日も配信するのでぜひ来てくださいね"
                else -> "今日はゲーム配信を始めますよろしくお願いしますみなさんこんばんは"
            }
        }
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        for (rev in 1..5) { p.submit(job(1, rev, false, rev)); Thread.sleep(700) }
        val draftsBeforeFinal = out.count { !it.isFinal }
        p.submit(job(1, 6, true, 6))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue("drafts shown while talking: $draftsBeforeFinal", draftsBeforeFinal >= 2)
        // queued stale drafts were merged: fewer MT calls than submitted drafts
        assertTrue("MT calls ${mt.calls}", mt.calls.size < 6)
        assertEquals("今日はゲーム配信を始めますよろしくお願いしますみなさんこんばんは", out.last().ja)
    }

    /** Reviewer repro: ASR slower than the draft interval, a newer draft is always queued when one finishes. */
    @Test
    fun slowAsrStillShowsDrafts() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val gate = java.util.concurrent.Semaphore(0)
        // the drafts grow like real ones (see Pipeline.DRAFT_MIN_NEW_CHARS): a recognition a couple of characters
        // longer than the last would rightly not be translated, and this test is about what the queue does
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String { gate.acquire(); return when (samples.size) {
                1 -> "あ"
                2 -> "今日はゲーム配信"
                3 -> "よろしくお願いします"
                4 -> "コメントありがとう"
                5 -> "スパチャもありがとうございます"
                else -> "スパチャもありがとうございますみなさん"
            } }
        }
        val p = Pipeline(asr, FakeMt(1), { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 1))
        for (rev in 2..5) {
            Thread.sleep(30)
            p.submit(job(1, rev, false, rev)) // newer draft queued *before* the running one finishes
            gate.release()
        }
        Thread.sleep(30)
        p.submit(job(1, 6, true, 6))
        gate.release(10)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue("drafts shown: ${out.filter { !it.isFinal }.map { it.revision }}", out.count { !it.isFinal } >= 3)
    }

    /**
     * A draft the recognizer only grew by a character or two would translate to the line already on screen (measured:
     * 54% of drafts grew by <=2 characters, 71% by <=5), so it is not translated at all (Pipeline.DRAFT_MIN_NEW_CHARS).
     * The next draft that does grow is translated, and a final is never skipped.
     */
    @Test
    fun aDraftThatBarelyGrewIsNotTranslated() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                1 -> "今日はゲーム配信を始めます"
                2 -> "今日はゲーム配信を始めますよ" // +2: not worth a call
                3 -> "今日はゲーム配信を始めますよろしくお願いします" // grows: translated
                else -> "今日はゲーム配信を始めますよろしくお願いしますみなさん"
            }
        }
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 1))
        val deadline = monoMs() + 2_000
        while (out.none { !it.isFinal } && monoMs() < deadline) Thread.sleep(5)
        val afterFirst = mt.calls.size // the first draft of a segment is always translated
        p.submit(job(1, 2, false, 2)); Thread.sleep(80)
        assertEquals("a draft that grew by 2 characters was translated", afterFirst, mt.calls.size)
        // the draft right after a skipped one is not recognized (Pipeline.skipNextDraft); the next one is
        p.submit(job(1, 3, false, 3)); Thread.sleep(80)
        p.submit(job(1, 4, false, 3))
        while (mt.calls.size <= afterFirst && monoMs() < deadline) Thread.sleep(5)
        assertEquals("the draft that grew was translated", afterFirst + 1, mt.calls.size)
        p.submit(job(1, 5, true, 4))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue("the final was translated", mt.calls.any { source(it).startsWith("今日はゲーム配信を始めますよろしくお願いしますみなさん") })
    }

    /** growth is measured from the draft last translated, not from one skipped: slow speech still gets drafts */
    @Test
    fun aSlowlyGrowingDraftIsTranslatedOnceItGrewEnoughSinceTheLastTranslation() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                1 -> "今日はゲーム"
                2 -> "今日はゲーム配信を" // +3: not worth a call
                else -> "今日はゲーム配信を始めま" // +3 on that one, +6 on the translated one
            }
        }
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, mt, { out.add(it) })
        p.start()
        try {
            p.submit(job(1, 1, false, 1))
            val deadline = monoMs() + 2_000
            while (out.none { !it.isFinal } && monoMs() < deadline) Thread.sleep(5)
            p.submit(job(1, 2, false, 2)); Thread.sleep(80)
            assertEquals(1, mt.calls.size)
            p.submit(job(1, 3, false, 3)); Thread.sleep(80) // not recognized: the draft before was skipped
            p.submit(job(1, 4, false, 3))
            while (mt.calls.size < 2 && monoMs() < deadline) Thread.sleep(5)
            assertEquals(2, mt.calls.size)
        } finally { p.stop(); p.awaitStopped() }
    }

    /** after a draft not worth translating, the segment's next draft is not even recognized; the one after is */
    @Test
    fun theDraftAfterOneNotWorthTranslatingIsNotRecognized() {
        val calls = Collections.synchronizedList(ArrayList<Int>())
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String { calls += samples.size; return when (samples.size) {
                1 -> "今日はゲーム"
                2 -> "今日はゲーム配信を" // +3: not worth a call
                4 -> "今日はゲーム配信を始めますよろしく"
                else -> "今日はゲーム配信を始めますよろしくお願いします"
            } }
        }
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, mt, { out.add(it) })
        p.start()
        try {
            val deadline = monoMs() + 2_000
            p.submit(job(1, 1, false, 1))
            while (out.none { !it.isFinal } && monoMs() < deadline) Thread.sleep(5)
            p.submit(job(1, 2, false, 2)); while (calls.size < 2 && monoMs() < deadline) Thread.sleep(5)
            p.submit(job(1, 3, false, 3)); Thread.sleep(80)
            p.submit(job(1, 4, false, 4)); while (mt.calls.size < 2 && monoMs() < deadline) Thread.sleep(5)
            // a final and a sentence-end candidate are always recognized
            p.submit(job(1, 5, false, 4)); while (calls.size < 4 && monoMs() < deadline) Thread.sleep(5) // +0: skipped
            p.submit(AsrJob(1, 6, false, FloatArray(5), monoMs(), endOfSpeech = true))
            while (calls.size < 5 && monoMs() < deadline) Thread.sleep(5)
            p.submit(job(1, 7, true, 5)); while (out.none { it.isFinal } && monoMs() < deadline) Thread.sleep(5)
            assertEquals(listOf(1, 2, 4, 4, 5, 5), calls.toList())
        } finally { p.stop(); p.awaitStopped() }
    }

    /** a queued draft a newer one may still replace is not the reference: the translator never saw it */
    @Test
    fun aQueuedDraftThatWasReplacedIsNotWhatTheNextIsMeasuredAgainst() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String = when (samples.size) {
                1 -> "あいうえお"
                2 -> "かきくけこ"
                else -> "あいうえおか" // +1 on the translated draft, +6 on the queued one
            }
        }
        val mt = GateMt()
        val p = Pipeline(asr, mt, {})
        p.start()
        try {
            p.submit(job(1, 1, false, 1))
            assertTrue(mt.entered.await(2, TimeUnit.SECONDS))
            p.submit(job(1, 2, false, 2)); Thread.sleep(80)
            p.submit(job(1, 3, false, 3)); Thread.sleep(80)
            mt.gate.countDown()
            val deadline = monoMs() + 2_000
            while (mt.order.size < 2 && monoMs() < deadline) Thread.sleep(5)
            Thread.sleep(50)
            assertEquals(listOf("あいうえお", "かきくけこ"), mt.order.toList())
        } finally { p.stop(); p.awaitStopped() }
    }

    /** a sentence-end candidate not worth translating does not make the next plain draft skipped */
    @Test
    fun aSentenceEndCandidateNotTranslatedDoesNotSkipTheNextDraft() {
        val calls = Collections.synchronizedList(ArrayList<Int>())
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String { calls += samples.size; return if (samples.size < 3) "今日はゲーム" else "今日はゲーム配信を始めます" }
        }
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, mt, { out.add(it) })
        p.start()
        try {
            val deadline = monoMs() + 2_000
            p.submit(job(1, 1, false, 1)); while (out.isEmpty() && monoMs() < deadline) Thread.sleep(5)
            p.submit(AsrJob(1, 2, false, FloatArray(2), monoMs(), endOfSpeech = true)); while (calls.size < 2 && monoMs() < deadline) Thread.sleep(5)
            p.submit(job(1, 3, false, 3)); while (calls.size < 3 && monoMs() < deadline) Thread.sleep(5)
            assertEquals(listOf(1, 2, 3), calls.toList())
        } finally { p.stop(); p.awaitStopped() }
    }

    @Test
    fun workerExceptionIsReportedAndPipelineRefusesNewJobs() {
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val asr = object : Asr { override fun recognize(samples: FloatArray): String = throw IllegalStateException("boom") }
        val p = Pipeline(asr, FakeMt(1), {}, onFailure = { failures.add(it) })
        p.start()
        assertTrue(p.submit(job(1, 1, true, 10)))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("boom", failures.single().message)
        assertFalse("must refuse after failure", p.submit(job(2, 1, true, 10)))
        p.stop(); p.awaitStopped()
    }

    @Test
    fun throwingCaptionSinkIsAFailureNotACrash() {
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val p = Pipeline(FakeAsr(), FakeMt(1), { throw java.io.IOException("disk full") }, onFailure = { failures.add(it) })
        p.start()
        p.submit(job(1, 1, true, 10))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("disk full", failures.single().message)
        p.stop(); p.awaitStopped()
    }

    @Test
    fun drainDeliversAcceptedFinalsAndDropsDrafts() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(FakeAsr(), FakeMt(100), { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2)); p.submit(job(3, 1, false, 3)); p.submit(job(3, 2, true, 4))
        p.submit(job(4, 1, false, 5))
        assertEquals(0, p.drainFinals(5_000))
        assertFalse("no new jobs after drain", p.submit(job(5, 1, true, 6)))
        p.stop(); p.awaitStopped()
        assertEquals(listOf(1L, 2L, 3L), out.filter { it.isFinal }.map { it.segmentId })
        assertTrue(out.none { it.segmentId == 4L })
    }

    @Test
    fun drainReportsWhatIsStillPending() {
        val p = Pipeline(FakeAsr(), FakeMt(2_000), {})
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        val left = p.drainFinals(200)
        p.stop(); p.awaitStopped()
        assertEquals(2, left) // one in MT, one queued
    }

    /** Review-2 #1: after a timed-out drain nothing may be delivered any more, so the reported count is final. */
    @Test
    fun drainTimeoutStopsWorkAndCountIsFinal() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val mt = FakeMt(300)
        val p = Pipeline(FakeAsr(), mt, { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        val left = p.drainFinals(80)
        val deliveredAtReturn = out.size
        Thread.sleep(800)
        assertEquals(2, left)
        assertEquals("nothing delivered after drain returned", deliveredAtReturn, out.size)
        assertEquals(0, out.size)
        assertTrue(mt.cancelled.get())
        assertEquals(0, mt.inside.get())
    }

    /** Review-2 #2: a stop during drain ends the drain immediately instead of waiting for the deadline. */
    @Test
    fun stopDuringDrainReturnsPromptly() {
        val p = Pipeline(FakeAsr(), FakeMt(10_000), {})
        p.start()
        p.submit(job(1, 1, true, 1)); p.submit(job(2, 1, true, 2))
        Thread({ Thread.sleep(100); p.stop() }).start()
        val t = System.nanoTime()
        val left = p.drainFinals(5_000)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("drain took ${ms}ms", ms < 1_000)
        assertEquals(2, left)
    }

    /** Review-2 #3: an interruption that is not caused by stop() is a worker failure. */
    @Test
    fun unexpectedInterruptIsAFailure() {
        val failures = AtomicInteger(0)
        val asr = object : Asr { override fun recognize(samples: FloatArray): String = throw InterruptedException("spurious") }
        val p = Pipeline(asr, FakeMt(1), {}, onFailure = { failures.incrementAndGet() })
        p.start()
        p.submit(job(1, 1, true, 1))
        val deadline = System.currentTimeMillis() + 2_000
        while (failures.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, failures.get())
        assertFalse(p.submit(job(2, 1, true, 2)))
        p.stop(); p.awaitStopped()
    }

    /** Review-2 #5: timestamp provenance travels with the job to the caption. */
    @Test
    fun captionCarriesTimestampProvenance() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(FakeAsr(), FakeMt(1), { out.add(it) })
        p.start()
        p.submit(AsrJob(1, 1, true, FloatArray(10), monoMs(), timeEstimated = true))
        p.submit(AsrJob(2, 1, true, FloatArray(20), monoMs(), timeEstimated = false))
        assertEquals(0, p.drainFinals(2_000))
        assertEquals(listOf(true, false), out.map { it.timeEstimated })
    }

    /** The UI measures latency from Caption.audioEndAt; a slow delivery must show up, not cancel out. */
    @Test
    fun captionCarriesCaptureTimeForUiLatency() {
        val done = CountDownLatch(1)
        var c: Caption? = null
        val p = Pipeline(FakeAsr(), FakeMt(1), { c = it; done.countDown() })
        p.start()
        val audioEnd = monoMs() - 500
        p.submit(AsrJob(1, 1, true, FloatArray(10), audioEnd))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        Thread.sleep(300) // UI thread busy before rendering
        assertEquals(audioEnd, c!!.audioEndAt)
        assertTrue(monoMs() - c!!.audioEndAt >= 800)
    }

    @Test
    fun overloadIsReportedAndNewJobsRefused() {
        val overloads = AtomicInteger()
        val p = Pipeline(FakeAsr(), FakeMt(10_000), {}, onOverload = { overloads.incrementAndGet() }, maxPendingAudioMs = 3_000)
        p.start()
        assertTrue(p.submit(job(1, 1, true, 16_000)))  // 1 s
        assertTrue(p.submit(job(2, 1, true, 16_000)))  // 2 s
        assertTrue(p.submit(job(3, 1, true, 32_000)))  // 4 s → overloaded (accepted, then flag)
        assertFalse(p.submit(job(4, 1, true, 16_000)))
        assertTrue(overloads.get() >= 1)
        p.stop(); p.awaitStopped()
    }

    /** Workers stuck in non-interruptible "native" code: awaitStopped must wait for them, and late results are dropped. */
    @Test
    fun awaitStoppedWaitsForNativeCallsAndDropsLateResults() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val asrEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asrInside = AtomicBoolean(false)
        val asr = object : Asr {
            override fun recognize(samples: FloatArray): String {
                asrInside.set(true); asrEntered.countDown()
                while (release.count > 0) { /* spin, ignores interrupt */ }
                asrInside.set(false)
                return "late"
            }
        }
        val mt = FakeMt(1)
        val p = Pipeline(asr, mt, { out.add(it) })
        p.start()
        p.submit(job(1, 1, true, 10))
        assertTrue(asrEntered.await(2, TimeUnit.SECONDS))
        p.stop()
        val waiter = Thread { p.awaitStopped() }.apply { start() }
        Thread.sleep(200)
        assertTrue("awaitStopped returned while ASR still running", waiter.isAlive && asrInside.get())
        release.countDown()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertFalse(asrInside.get())
        assertEquals(0, mt.inside.get())
        assertTrue(out.isEmpty())
    }

    @Test
    fun timingIsFilled() {
        val done = CountDownLatch(1)
        var c: Caption? = null
        val p = Pipeline(FakeAsr(20), FakeMt(30), { c = it; done.countDown() })
        p.start()
        val audioEnd = monoMs() - 100 // the segmenter handed it over 100 ms after the audio ended
        p.submit(AsrJob(1, 1, true, FloatArray(16_000), audioEnd))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        val t = c!!.timing
        assertEquals(1000L, t.audioMs)
        assertTrue("wait ${t.segmentWaitMs}", t.segmentWaitMs >= 100)
        assertTrue(t.asrMs >= 20 && t.mtMs >= 30)
        assertTrue(t.mtDoneMs >= t.segmentWaitMs + t.asrMs + t.mtMs)
    }

    // --- Jev ---

    private class FakeJudge(val unfinishedP: Double? = 0.9, val choice: String? = "ok", val confidence: Double = 0.95,
                            val delayMs: Long = 0) : Judge {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val cancelled = AtomicBoolean(false)
        private fun call(answer: Map<String, Any?>?): JevCall {
            // like the real client: stop (interrupt here, disconnect there) ends the call with an error, not an exception
            try { Thread.sleep(delayMs) } catch (e: InterruptedException) { return JevCall("{req}", 0, null, 0, "cancelled", null) }
            return JevCall("{req}", if (answer == null) 0 else 200, answer?.let { Json.encode(it) }, delayMs,
                if (answer == null) "timeout" else null, answer)
        }
        override fun unfinished(text: String): JevCall { asked.add("u:$text"); return call(unfinishedP?.let { mapOf("noul" to it) }) }
        override fun mistranslation(ja: String, zh: String): JevCall {
            asked.add("m:$ja|$zh"); return call(choice?.let { mapOf("choice" to it, "confidence" to confidence) })
        }
        override fun cancel() = cancelled.set(true)
    }

    private fun runFinal(judge: Judge, events: MutableList<Map<String, Any?>> = Collections.synchronizedList(ArrayList()),
                         mt: FakeMt = FakeMt(1), check: Boolean = true): List<Caption> {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() }, judge = judge, checkTranslations = check, log = { events.add(it) })
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        return out
    }

    @Test
    fun reliableErrorClassIsTranslatedOnceMoreWithItsNote() {
        val mt = FakeMt(1)
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val out = runFinal(FakeJudge(choice = "negation"), events, mt)
        assertEquals(2, mt.calls.size)
        assertTrue(mt.calls[1].contains(FIX_NOTES["negation"]!!))
        // the first translation is shown as a draft while Jev checks it, then the final replaces it
        assertEquals(listOf(false to 3, true to 4), out.map { it.isFinal to it.revision })
        val b = CaptionBoard(); out.forEach { b.accept(it) }
        assertTrue(b.snapshot().single().isFinal)
        val mts = events.filter { it["ev"] == "mt" }
        assertEquals(listOf(1, 2), mts.map { it["attempt"] })
        assertEquals("retranslate:negation", events.single { it["ev"] == "jev" }["action"])
    }

    @Test
    fun otherClassesLowConfidenceAndFailuresKeepTheFirstTranslation() {
        for (judge in listOf(FakeJudge(choice = "ok"), FakeJudge(choice = "platform_term"),
                             FakeJudge(choice = "negation", confidence = 0.4), FakeJudge(choice = null))) {
            val mt = FakeMt(1)
            val out = runFinal(judge, mt = mt)
            assertEquals(1, mt.calls.size)
            assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
        }
    }

    @Test
    fun withoutTheCheckSwitchAFinalIsNotCheckedAndKeepsItsRevision() {
        val mt = FakeMt(1); val judge = FakeJudge(choice = "negation")
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val out = runFinal(judge, events, mt, check = false)
        assertEquals(1, mt.calls.size)
        assertTrue(judge.asked.toString(), judge.asked.none { it.startsWith("m:") })
        assertEquals(listOf(true to 3), out.map { it.isFinal to it.revision })
        assertTrue(events.none { it["ev"] == "jev" })
    }

    /** A judge whose mistranslation answer waits for [gate]; unfinished() is not used here. */
    private class GateJudge(val choice: String = "ok") : Judge {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1); val calls = AtomicInteger(0)
        override fun unfinished(text: String) = JevCall("{req}", 0, null, 0, "unused", null)
        override fun mistranslation(ja: String, zh: String): JevCall {
            calls.incrementAndGet(); entered.countDown(); gate.await()
            val a = mapOf("choice" to choice, "confidence" to 0.95)
            return JevCall("{req}", 200, Json.encode(a), 0, null, a)
        }
        override fun cancel() = gate.countDown()
    }

    @Test
    fun aSlowCheckDoesNotHoldBackTheNextSegmentsDraft() {
        val judge = GateJudge()
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val draft2 = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), FakeMt(1), { out.add(it); if (it.segmentId == 2L && !it.isFinal) draft2.countDown() },
            judge = judge, checkTranslations = true)
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
        p.submit(job(2, 1, false, 20))
        assertTrue("the draft of segment 2 must appear while segment 1 is still being checked", draft2.await(2, TimeUnit.SECONDS))
        assertTrue(out.none { it.isFinal })
        judge.gate.countDown()
        p.stop(); p.awaitStopped()
    }

    @Test
    fun withChecksFinalsStayInOrderAlsoWhenALaterOneFailsOrIsEmpty() {
        val judge = GateJudge()
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(4)
        // seg 2 recognizes nothing (empty final), seg 3 fails to translate, seg 4 is a sentinel: the MT thread is
        // serial, so once it translates seg 4 it has finished handling seg 3
        val asr = object : Asr { override fun recognize(samples: FloatArray) = if (samples.size == 20) "" else "ja${samples.size}" }
        val sentinel = CountDownLatch(1)
        val mt = object : Mt {
            override fun translate(prompt: String): MtResult? {
                if (source(prompt) == "ja40") sentinel.countDown()
                return if (source(prompt) == "ja30") null else MtResult("zh(${source(prompt)})", 'e', 1, 1)
            }
            override fun cancel() {}
        }
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() }, judge = judge, checkTranslations = true)
        p.start()
        try {
            for ((s, n) in listOf(1L to 10, 2L to 20, 3L to 30, 4L to 40)) p.submit(job(s, if (s == 1L) 3 else 1, true, n))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            assertTrue("segments 2..4 are handled while segment 1 waits for its check", sentinel.await(2, TimeUnit.SECONDS))
            assertTrue(out.none { it.isFinal })
            judge.gate.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        assertEquals(listOf(1L, 2L, 3L, 4L), out.filter { it.isFinal }.map { it.segmentId })
        val b = CaptionBoard(10); out.forEach { b.accept(it) }
        assertEquals(listOf(1L, 3L, 4L), b.snapshot().filter { it.isFinal }.map { it.segmentId }) // the empty one is not shown
        assertTrue(out.single { it.isFinal && it.segmentId == 3L }.zh.startsWith("[翻译失败]"))
        assertEquals(2, judge.calls.get()) // segments 1 and 4: a failed or empty translation is not checked
    }

    @Test
    fun aRetranslationNeverRunsAlongsideAnotherTranslation() {
        val inside = AtomicInteger(0); val maxInside = AtomicInteger(0)
        val threads = Collections.synchronizedSet(HashSet<String>()); val calls = Collections.synchronizedList(ArrayList<String>())
        val mt = object : Mt {
            override fun translate(prompt: String): MtResult? {
                val n = inside.incrementAndGet(); maxInside.accumulateAndGet(n, ::maxOf)
                try { threads.add(Thread.currentThread().name); calls.add(prompt); Thread.sleep(20); return MtResult("zh(x)", 'e', 1, 1) }
                finally { inside.decrementAndGet() }
            }
            override fun cancel() {}
        }
        val done = CountDownLatch(3)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() }, judge = FakeJudge(choice = "negation"), checkTranslations = true)
        p.start()
        try {
            for (s in 1L..3L) { p.submit(job(s, 1, false, s.toInt() * 10 + 1)); p.submit(job(s, 2, true, s.toInt() * 10)) }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally { p.stop(); p.awaitStopped() }
        assertEquals(1, maxInside.get())
        assertEquals(setOf("jpsub-mt"), threads.toSet())
        assertEquals(3, calls.count { it.contains(FIX_NOTES["negation"]!!) })
    }

    @Test
    fun stoppingWhileACheckWaitsForItsRetranslationEndsAndDrainReportsIt() {
        // the retranslation (its prompt carries the fix note) blocks until the translator is cancelled by stop
        val cancelled = CountDownLatch(1); val retrying = CountDownLatch(1)
        val mt = object : Mt {
            override fun translate(prompt: String): MtResult? {
                if (prompt.contains(FIX_NOTES["negation"]!!)) { retrying.countDown(); cancelled.await(); return null }
                return MtResult("zh(x)", 'e', 1, 1)
            }
            override fun cancel() = cancelled.countDown()
        }
        val p = Pipeline(FakeAsr(), mt, {}, judge = FakeJudge(choice = "negation"), checkTranslations = true)
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(retrying.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        assertEquals(1, p.drainFinals(300)) // not delivered, counted once
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000) // the waiting check thread did not hang the stop
    }

    @Test
    fun aRetranslationThatThrowsFailsThePipelineInsteadOfPublishingTheFinal() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val failure = CountDownLatch(1)
        val mt = object : Mt {
            override fun translate(prompt: String): MtResult? {
                if (prompt.contains(FIX_NOTES["negation"]!!)) throw IllegalStateException("native crash")
                return MtResult("zh(x)", 'e', 1, 1)
            }
            override fun cancel() {}
        }
        val p = Pipeline(FakeAsr(), mt, { out.add(it) }, onFailure = { failure.countDown() },
            judge = FakeJudge(choice = "negation"), checkTranslations = true)
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(failure.await(2, TimeUnit.SECONDS))
        p.stop(); p.awaitStopped()
        assertTrue(out.none { it.isFinal })
    }

    @Test
    fun timedPiecesFollowTheTextInJapaneseAndEnglish() {
        // Japanese tokens are byte-level BPE: one character per UTF-8 byte ("今" is 3 bytes), "▁" where no space is
        val ja = Recognition.of("今日は", arrayOf("▁abc", "▁def", "ghi"), floatArrayOf(0f, 0.8f, 0.9f), 0.3f)
        assertEquals(listOf("今", "日", "は"), ja.pieces!!.map { it.text })
        // the first token comes at frame 0: it is put just before the second one
        assertEquals(listOf(0.4f, 0.5f, 0.6f), ja.pieces!!.map { Math.round(it.t * 100) / 100f })
        // a character split over two tokens is one piece, at the time of the token with its first byte
        assertEquals(listOf("今", "日"), Recognition.of("今日", arrayOf("▁ab", "c", "▁def"), floatArrayOf(0.3f, 0.4f, 0.5f), 0.3f).pieces!!.map { it.text })
        val split = Recognition.of("今日", arrayOf("▁abcd", "ef"), floatArrayOf(0.2f, 0.6f), 0f).pieces!!
        assertEquals(listOf("今" to 0.2f, "日" to 0.2f), split.map { it.text to it.t })
        // English: plain text, "▁" is the space before a word
        val en = Recognition.of("THANK YOU", arrayOf("▁THAN", "K", "▁YOU"), floatArrayOf(0.3f, 0.5f, 0.7f), 0.3f)
        assertEquals("THANK YOU", en.pieces!!.joinToString("") { it.text })
        assertEquals(listOf("THAN", "K", " YOU"), en.pieces!!.map { it.text })
        // tokens that do not fit the text: no timings
        assertEquals(null, Recognition.of("今日", arrayOf("▁abc"), floatArrayOf(0.3f), 0.3f).pieces)
        assertEquals(null, Recognition.of("今日", arrayOf("▁abc", "▁def"), floatArrayOf(0.3f), 0.3f).pieces)
    }

    /** one piece per character from [t0], [step] s apart (a Latin word is one piece, its space in front) */
    private fun pieces(s: String, t0: Float, step: Float): List<Piece> =
        (if (s.contains(' ')) s.split(' ').mapIndexed { i, w -> if (i == 0) w else " $w" } else s.map { it.toString() })
            .mapIndexed { i, x -> Piece(x, t0 + i * step) }

    @Test
    fun aRepeatAtTheJoinIsDroppedOnlyWhenTheUserAskedForIt() {
        // the draft's last words and the new result's first ones are the same words, recognized twice
        val draft = pieces("おいしいってだれ火の石って", 0f, 0.15f)
        val whole = pieces("火の意志ってどういうこと", 2.0f, 0.12f)
        assertEquals("おいしいってだれ火の意志ってどういうこと", Pipeline.textOf(Pipeline.repairLoss(whole, draft, 3.5f, true)!!))
        assertEquals("おいしいってだれ火の石って火の意志ってどういうこと", Pipeline.textOf(Pipeline.repairLoss(whole, draft, 3.5f, false)!!))
        // a single shared character: "頑張" + "張ってください" (the whole's first piece "頑" repeats the block's end)
        val d2 = pieces("かなえてください頑張", 0f, 0.15f)
        val w2 = listOf(Piece("頑", 1.9f)) + pieces("張ってください", 2.0f, 0.12f)
        assertEquals("かなえてください頑張ってください", Pipeline.textOf(Pipeline.repairLoss(w2, d2, 3.0f, true)!!))
        // two end characters of which only the first matches are no repeat
        val w3 = listOf(Piece("頑張", 1.9f)) + pieces("ってください", 2.0f, 0.12f)
        assertEquals("ありがとうございます頑ば頑張ってください",
            Pipeline.textOf(Pipeline.repairLoss(w3, pieces("ありがとうございます頑ば", 0f, 0.15f), 3.0f, true)!!))
    }

    @Test
    fun englishWordsBroughtBackAreJoinedWithASpace() {
        val draft = pieces("THANK YOU FOR THE SUPER CHAT", 0f, 0.25f)
        val whole = pieces("SEE YOU TOMORROW", 2.0f, 0.3f)
        assertEquals("THANK YOU FOR THE SUPER CHAT SEE YOU TOMORROW", Pipeline.textOf(Pipeline.repairLoss(whole, draft, 3.0f, false)!!))
        // join: a space between Latin words only; Japanese, also with a Latin word in it, stays unspaced
        assertEquals("THANK YOU SO MUCH", Pipeline.join("THANK YOU", "SO MUCH"))
        assertEquals("THANK YOU. SO MUCH", Pipeline.join("THANK YOU.", "SO MUCH"))
        assertEquals("ありがとうhello", Pipeline.join("ありがとう", "hello"))
        assertEquals("SCありがとう", Pipeline.join("SC", "ありがとう"))
    }

    @Test
    fun wordsMissingInsideTheNewResultAreBroughtBackButNotAfterItsEnd() {
        val draft = pieces("今日はゲーム配信をします", 0f, 0.15f)
        // nothing between 0.3 s and 1.2 s, where the draft had "ゲーム配信" (0.45 .. 1.05): put back there
        val whole = pieces("今日は", 0f, 0.15f) + pieces("をします", 1.2f, 0.15f)
        assertEquals("今日はゲーム配信をします", Pipeline.textOf(Pipeline.repairLoss(whole, draft, 2.5f, false)!!))
        // the new result ends before the draft did: a draft's last words are often reworded, nothing is added
        assertEquals(null, Pipeline.repairLoss(pieces("今日はゲーム配信", 0f, 0.15f), draft, 2.5f, false))
        // a correction of the draft at the same time ("祖父" -> "そうそう") is not a gap
        assertEquals(null, Pipeline.repairLoss(pieces("そうそうだよね", 0f, 0.12f), pieces("祖父だよね", 0f, 0.15f), 1.0f, false))
    }

    @Test
    fun wordsTheNewResultStillHasAreNotPutBack() {
        // one long first piece (its time a guess, so the whole audio looks like a leading stretch): no loss
        assertEquals(null, Pipeline.repairLoss(listOf(Piece("THERE", -0.3f)), listOf(Piece("THERE", -0.3f)), 2f, false))
        assertEquals(null, Pipeline.repairLoss(listOf(Piece("THANK", 0f), Piece(" YOU", 0.5f), Piece(" FOR", 0.9f)),
            listOf(Piece("THANK", 0f)), 1.5f, false))
        assertEquals(null, Pipeline.repairLoss(listOf(Piece("今日はゲーム配信", 0f), Piece("をします", 1.2f)),
            pieces("今日はゲーム配信", 0f, 0.1f), 2.5f, false))
        // a correction ("祖父" -> "そうそう") whose second piece comes late
        assertEquals(null, Pipeline.repairLoss(listOf(Piece("そうそう", 0f)) + pieces("家に行った", 0.9f, 0.1f),
            pieces("祖父の家に行った", 0f, 0.12f), 1.5f, false))
        // the same long word tokenized differently: the draft's second half is no gap of the new result
        assertEquals(null, Pipeline.repairLoss(
            listOf(Piece("A", 0f), Piece("B", 0.1f), Piece("EXTRAORDINARY", 1.0f), Piece(" NEXT", 1.8f)),
            listOf(Piece("A", 0f), Piece("B", 0.1f), Piece("EX", 1.0f), Piece("TRAORDINARY", 1.2f), Piece(" NEXT", 1.8f)), 3f, false))
        // a single piece that is in the lost block (replayed seg 122: "日"): the block only
        assertEquals("矢見さんスバチありが日の目の部屋", Pipeline.textOf(Pipeline.repairLoss(listOf(Piece("日", -0.3f)),
            pieces("矢見さんスバチありが日の目の部屋", 0f, 0.1f), 2.0f, false)!!))
        // with dedupe, Latin words are not matched letter by letter: "HERE" is no repeat of "THERE"
        assertEquals("WE WERE THERE HERE WE GO", Pipeline.textOf(Pipeline.repairLoss(
            listOf(Piece("HERE", 2.2f), Piece(" WE", 2.5f), Piece(" GO", 2.6f)), pieces("WE WERE THERE", 0f, 0.5f), 4f, true)!!))
    }

    @Test
    fun theTranslatorGetsTheLastTwoFinishedSentencesAsContext() {
        val mt = FakeMt(1); val done = CountDownLatch(4)
        val asr = object : Asr { override fun recognize(samples: FloatArray) = if (samples.size == 30) "" else "ja${samples.size}" }
        val p = Pipeline(asr, mt, { if (it.isFinal) done.countDown() })
        p.start()
        for ((s, n) in listOf(1L to 10, 2L to 20, 3L to 30, 4L to 40)) p.submit(job(s, 1, true, n))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        val ctx = "【前文】\n"
        assertTrue(mt.calls[0], !mt.calls[0].contains(ctx)) // the first sentence has none
        assertTrue(mt.calls[1], mt.calls[1].startsWith(context("ja10")))
        // seg 3 is empty (not translated, not context); seg 4 sees the two before it that had text
        assertEquals(3, mt.calls.size)
        assertTrue(mt.calls[2], mt.calls[2].startsWith(context("ja10", "ja20")))
        assertTrue(mt.calls.none { it.startsWith("【前文】\nja10\nja20\nja") }) // never more than two
    }

    @Test
    fun aSegmentOfOnlyPunctuationIsNotTranslatedNorContext() {
        // recognition may end a segment on a lone mark; a translator asked about "。" answers with commentary
        val mt = FakeMt(1); val out = Collections.synchronizedList(ArrayList<Caption>()); val done = CountDownLatch(3)
        val asr = object : Asr { override fun recognize(samples: FloatArray) = if (samples.size == 20) " 。…" else "ja${samples.size}" }
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        for ((s, n) in listOf(1L to 10, 2L to 20, 3L to 30)) p.submit(job(s, 1, true, n))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(2, mt.calls.size)
        assertTrue(mt.calls[1], mt.calls[1].startsWith(context("ja10")) && !mt.calls[1].contains("…"))
        assertEquals("", out.single { it.isFinal && it.segmentId == 2L }.zh)
    }

    @Test
    fun aDraftOfOnlyPunctuationIsNotShown() {
        // a draft has nothing to replace: one with an empty translation would be an empty line on screen
        val mt = FakeMt(1); val out = Collections.synchronizedList(ArrayList<Caption>()); val done = CountDownLatch(1)
        val asr = object : Asr { override fun recognize(samples: FloatArray) = if (samples.size == 20) "。" else "ja${samples.size}" }
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, false, 20)); Thread.sleep(200)
        p.submit(job(1, 2, true, 30))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertTrue(out.map { it.ja to it.zh }.toString(), out.none { !it.isFinal })
        assertEquals(1, mt.calls.size)
    }

    @Test
    fun onlyTheLastTwoSentencesAreContextAndALateRetryKeepsItsOwn() {
        // segment 1's check is held while 2..11 are translated (far enough to have evicted an old per-segment
        // context store); its retry must still have no context, as its first translation had none
        val judge = GateJudge(choice = "negation")
        val mt = FakeMt(1); val done = CountDownLatch(11)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() }, judge = judge, checkTranslations = true)
        p.start()
        try {
            p.submit(job(1, 1, true, 10))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            for (s in 2L..11L) p.submit(job(s, 1, true, s.toInt() * 10))
            val t0 = System.nanoTime()
            while (mt.calls.size < 11 && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(5)
            assertEquals("segments 2..11 translated while 1 waits for its check", 11, mt.calls.size)
            judge.gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        val ctx = "【前文】\n"
        assertTrue(mt.calls[3], mt.calls[3].startsWith(context("ja20", "ja30"))) // segment 4: the last two only
        val retry1 = mt.calls.first { it.contains(FIX_NOTES["negation"]!!) && source(it) == "ja10" }
        assertTrue(retry1, !retry1.contains(ctx))
        val retry2 = mt.calls.first { it.contains(FIX_NOTES["negation"]!!) && source(it) == "ja20" }
        // its own context (just segment 1), not the newest sentences
        assertEquals(retry2, "ja10", retry2.substringAfter(ctx).substringBefore("\n\n"))
    }

    @Test
    fun contextCanBeTurnedOffOrSetFromOneToFiveSentences() {
        val ctx = "【前文】\n"
        fun run(n: Int): List<String> {
            val mt = FakeMt(1); val done = CountDownLatch(7)
            val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() }, previousSentences = n)
            p.start()
            try {
                for (s in 1L..7L) p.submit(job(s, 1, true, s.toInt() * 10))
                assertTrue(done.await(2, TimeUnit.SECONDS))
            } finally { p.stop(); p.awaitStopped() }
            return mt.calls
        }
        // 0 = off: no sentence gets a context
        run(0).forEach { assertTrue(it, !it.contains(ctx)) }
        // 1: only the sentence right before
        val one = run(1)
        assertEquals(one[6], "ja60", one[6].substringAfter(ctx).substringBefore("\n\n"))
        // 5: the five before, oldest first, never six
        val five = run(Pipeline.MAX_PREVIOUS_SENTENCES)
        assertTrue(five[6], five[6].startsWith(context("ja20", "ja30", "ja40", "ja50", "ja60")))
        assertTrue(five[6], !five[6].contains("ja10"))
    }

    @Test
    fun aLateRetryKeepsItsOwnContextWithFiveSentences() {
        val ctx = "【前文】\n"
        val judge = GateJudge(choice = "negation")
        val mt = FakeMt(1); val done = CountDownLatch(8)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() }, judge = judge, checkTranslations = true,
            previousSentences = 5)
        p.start()
        try {
            p.submit(job(1, 1, true, 10))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            for (s in 2L..8L) p.submit(job(s, 1, true, s.toInt() * 10))
            val t0 = System.nanoTime()
            while (mt.calls.size < 8 && System.nanoTime() - t0 < 2_000_000_000L) Thread.sleep(5)
            judge.gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        // segment 1 had no context on its first translation, so its retry has none either
        val retry1 = mt.calls.first { it.contains(FIX_NOTES["negation"]!!) && source(it) == "ja10" }
        assertTrue(retry1, !retry1.contains(ctx))
    }

    @Test
    fun endOfSpeechDraftIsJudgedAndTheAnswerIsKeyedByRevision() {
        val judge = FakeJudge(unfinishedP = 0.9)
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge)
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(true, p.holdHint(1, 2))
        assertEquals(null, p.holdHint(1, 3))
        p.stop(); p.awaitStopped()
        assertTrue(judge.cancelled.get())
    }

    /**
     * A judge the service limited: while [limited] every question returns at once without asking, like [Jev] while
     * paused; otherwise each question gets the 429 itself (sent, answered with a limit).
     */
    private class PausedJudge(val limited: Boolean = true) : Judge {
        val asked = Collections.synchronizedList(ArrayList<String>())
        val limit = JevLimit(Long.MAX_VALUE, null, free = true)
        private fun call() = if (limited) JevCall("{req}", 0, null, 0, "paused", null, limit)
            else JevCall("{req}", 429, "{}", 5, "HTTP 429", null, limit)
        override fun unfinished(text: String): JevCall { asked.add("u:$text"); return call() }
        override fun mistranslation(ja: String, zh: String): JevCall { asked.add("m:$ja|$zh"); return call() }
        override fun cancel() {}
        override fun paused(): JevLimit? = if (limited) limit else null
    }

    @Test
    fun aPausedJudgeIsNotAskedAndTheSegmenterCutsAtOnce() {
        val judge = PausedJudge()
        val services = Collections.synchronizedList(ArrayList<Pair<Boolean, ServiceCall>>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, onService = { j, c -> services.add(j to c) })
        p.start()
        // a new utterance is cut by pauses only, as without Jev
        assertFalse(p.judged(1))
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        // "finished" at once, not null until the wait runs out
        assertEquals(false, p.holdHint(1, 2))
        p.stop(); p.awaitStopped()
        assertTrue(judge.asked.isEmpty())
    }

    @Test
    fun aLimitAnswerFinishesTheCandidateAndKeepsTheTranslation() {
        // the 429 itself comes back from the question: finished, and the check keeps the first translation
        val judge = PausedJudge(limited = false)
        val services = Collections.synchronizedList(ArrayList<Pair<Boolean, ServiceCall>>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, onService = { j, c -> services.add(j to c) })
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(false, p.holdHint(1, 2))
        p.stop(); p.awaitStopped()
        val jev = services.filter { it.first }.map { it.second }
        assertTrue(jev.isNotEmpty())
        assertTrue(jev.all { !it.ok && it.limit != null })

        val mt = FakeMt(1)
        val out = runFinal(PausedJudge(limited = false), mt = mt)
        assertEquals(1, mt.calls.size)
        assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
    }

    @Test
    fun aReusedTranslationIsNotReportedAsARequest() {
        val services = Collections.synchronizedList(ArrayList<Pair<Boolean, ServiceCall>>())
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val mt = FakeMt(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() }, onService = { j, c -> services.add(j to c) })
        p.start()
        p.submit(job(1, 1, false, 10)); Thread.sleep(100)
        p.submit(job(1, 2, true, 10)) // same text: the draft's translation is reused
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, mt.calls.size)
        assertEquals(1, services.size)
        assertFalse(services[0].first); assertTrue(services[0].second.ok)
    }

    /**
     * Its questions wait on [gate]; [limit] switched on from outside stands for a 429 another question got meanwhile.
     * The answer that comes back after it is an older request's: "hold" / a fix class with high confidence.
     */
    private class LateJudge : Judge {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        @Volatile var limit: JevLimit? = null
        /** a 429 came after the questions went out (generation 0); with [limit] null too: a pause already over */
        @Volatile var gen = 0
        val asked = Collections.synchronizedList(ArrayList<String>())
        override fun unfinished(text: String): JevCall {
            asked.add("u"); entered.countDown(); gate.await()
            return JevCall("{req}", 200, "{}", 1, null, mapOf("noul" to 0.95), gen = 0)
        }
        override fun mistranslation(ja: String, zh: String): JevCall {
            asked.add("m"); entered.countDown(); gate.await()
            return JevCall("{req}", 200, "{}", 1, null, mapOf("choice" to "negation", "confidence" to 0.99), gen = 0)
        }
        override fun cancel() { gate.countDown() }
        override fun paused(): JevLimit? = limit
        override fun limitedAfter(gen: Int) = gen >= 0 && this.gen > gen
    }

    @Test
    fun aValidAnswerDuringAnInstallingLimitIsLimitedNotUnusable() {
        val judge = LateJudge()
        val services = Collections.synchronizedList(ArrayList<ServiceCall>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, onService = { j, c -> if (j) services.add(c) })
        p.start()
        try {
            assertTrue(p.judged(1))
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            judge.limit = JevLimit.INSTALLING; judge.gen = 1 // a 429 arrived, its end not read yet
            judge.gate.countDown() // the older question's valid 200
            val deadline = monoMs() + 2000
            while (services.isEmpty() && monoMs() < deadline) Thread.sleep(5)
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        val c = services.single()
        assertEquals(200, c.http)
        assertTrue(c.limited && !c.ok)
        assertNull(c.limit) // the placeholder is not kept
        assertEquals(ServiceStatus.Light.YELLOW, ServiceStatus.light(c, true))
        // installed later and over: the warning says the next sentence tries again, not "回答无法使用"
        val shown = ServiceStatus.jevShown(c, null, JevLimit(0, null, false), 0)!!
        assertEquals("请求受限，下一句重试", ServiceStatus.reason(shown, true, 1, System.currentTimeMillis()))
        // not installed by the time it is shown (no limit known): still limited
        assertEquals("请求受限，稍后重试", ServiceStatus.reason(ServiceStatus.jevShown(c, null, null, 0)!!, true, 1, 0))
    }

    @Test
    fun aLimitWhileAQuestionIsOutCutsAtOnceAndItsLateHoldIsNotUsed() {
        val judge = LateJudge()
        val services = Collections.synchronizedList(ArrayList<ServiceCall>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, onService = { j, c -> if (j) services.add(c) })
        p.start()
        try {
            assertTrue(p.judged(1)) // fixes segment 1's setup with this judge
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            assertNull(p.holdHint(1, 2)) // waiting for the answer
            judge.limit = JevLimit(Long.MAX_VALUE, null, true) // the check got a 429 meanwhile
            assertEquals(false, p.holdHint(1, 2)) // cut now, not when the wait runs out
            judge.gate.countDown() // the old question answers "unfinished"
            val deadline = monoMs() + 2000
            while (services.isEmpty() && monoMs() < deadline) Thread.sleep(5)
            assertEquals(false, p.holdHint(1, 2))
            // reported as limited, not as a success
            assertTrue(services.single().limit != null && !services.single().ok)
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
    }

    @Test
    fun aCheckAnsweredAfterALimitDoesNotRetranslate() {
        val judge = LateJudge()
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() }, judge = judge, judgeEnds = false,
            checkTranslations = true)
        p.start()
        try {
            p.submit(job(1, 3, true, 10))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            judge.limit = JevLimit(Long.MAX_VALUE, null, true)
            judge.gate.countDown() // "negation, 0.99" from before the limit
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        assertEquals(1, mt.calls.size)
        assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
    }

    @Test
    fun aLateAnswerAfterAShortPauseEndedStillDoesNotHoldOrRetranslate() {
        // a 429 with Retry-After 0 (or a short pause already over) while the questions were out: no pause now, but
        // their answers are from before it
        val judge = LateJudge()
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge)
        p.start()
        try {
            assertTrue(p.judged(1))
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            judge.gen = 1
            judge.gate.countDown()
            val deadline = monoMs() + 2000
            while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
            assertEquals(false, p.holdHint(1, 2))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }

        val judge2 = LateJudge()
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p2 = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() }, judge = judge2, judgeEnds = false,
            checkTranslations = true)
        p2.start()
        try {
            p2.submit(job(1, 3, true, 10))
            assertTrue(judge2.entered.await(2, TimeUnit.SECONDS))
            judge2.gen = 1
            judge2.gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { judge2.gate.countDown(); p2.stop(); p2.awaitStopped() }
        assertEquals(1, mt.calls.size)
    }

    @Test
    fun aCachedHoldIsDroppedByALaterLimit() {
        val judge = FakeJudgeWithGen()
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge)
        p.start()
        try {
            assertTrue(p.judged(1))
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            val deadline = monoMs() + 2000
            while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
            assertEquals(true, p.holdHint(1, 2))
            judge.gen = 1 // a 429 elsewhere, its pause already over
            assertEquals(false, p.holdHint(1, 2))
        } finally { p.stop(); p.awaitStopped() }
    }

    /** "negation, 0.99" from generation 0, after [gate]; [gen] raised from outside stands for a later 429. */
    private class FixJudge : Judge {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        @Volatile var gen = 0
        override fun unfinished(text: String) = JevCall("{req}", 0, null, 0, "unused", null)
        override fun mistranslation(ja: String, zh: String): JevCall {
            entered.countDown(); gate.await()
            return JevCall("{req}", 200, "{}", 1, null, mapOf("choice" to "negation", "confidence" to 0.99), gen = 0)
        }
        override fun cancel() { gate.countDown() }
        override fun limitedAfter(gen: Int) = gen >= 0 && this.gen > gen
    }

    /** Waits on [gate] in the call [blocks] picks; records every prompt. */
    private class PickMt(val blocks: (String) -> Boolean) : Mt {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val prompts = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            prompts.add(prompt)
            if (blocks(prompt)) { entered.countDown(); gate.await() }
            return MtResult("zh(${source(prompt)})" + if (prompt.contains(FIX_NOTES["negation"]!!)) "-fixed" else "", 'e', 1, 1)
        }
        override fun cancel() = gate.countDown()
    }

    @Test
    fun aRetranslationQueuedBeforeALimitDoesNotRun() {
        // segment 2's translation keeps the MT thread busy while segment 1's check asks for a retranslation
        val judge = FixJudge()
        val mt = PickMt { source(it) == "ja20" }
        val finals = Collections.synchronizedList(ArrayList<Caption>())
        // logged once the answer was accepted, right before the retry is queued
        val asked = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) finals.add(it) }, judge = judge, judgeEnds = false, checkTranslations = true,
            log = { if (it["action"] == "retranslate:negation") asked.countDown() })
        p.start()
        try {
            p.submit(job(1, 3, true, 10))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            p.submit(job(2, 1, true, 20))
            assertTrue(mt.entered.await(2, TimeUnit.SECONDS))
            judge.gate.countDown() // the fix is queued behind segment 2
            assertTrue(asked.await(2, TimeUnit.SECONDS))
            judge.gen = 1 // a 429 before the MT thread gets to it
            mt.gate.countDown()
            val deadline = monoMs() + 3000
            while (finals.none { it.segmentId == 1L } && monoMs() < deadline) Thread.sleep(5)
        } finally { judge.gate.countDown(); mt.gate.countDown(); p.stop(); p.awaitStopped() }
        assertTrue(mt.prompts.none { it.contains(FIX_NOTES["negation"]!!) })
        assertEquals("zh(ja10)", finals.last { it.segmentId == 1L }.zh)
    }

    @Test
    fun aRetranslationRunningWhenALimitComesIsNotUsed() {
        val judge = FixJudge().apply { gate.countDown() }
        val mt = PickMt { it.contains(FIX_NOTES["negation"]!!) }
        val finals = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) finals.add(it) }, judge = judge, judgeEnds = false, checkTranslations = true)
        p.start()
        try {
            p.submit(job(1, 3, true, 10))
            assertTrue(mt.entered.await(2, TimeUnit.SECONDS))
            judge.gen = 1 // a 429 while the retranslation runs
            mt.gate.countDown()
            val deadline = monoMs() + 3000
            while (finals.isEmpty() && monoMs() < deadline) Thread.sleep(5)
        } finally { mt.gate.countDown(); p.stop(); p.awaitStopped() }
        assertEquals("zh(ja10)", finals.last().zh)
    }

    private class FakeJudgeWithGen : Judge {
        @Volatile var gen = 0
        override fun unfinished(text: String) = JevCall("{req}", 200, "{}", 1, null, mapOf("noul" to 0.95), gen = 0)
        override fun mistranslation(ja: String, zh: String) = JevCall("{req}", 200, "{}", 1, null, mapOf("choice" to "ok", "confidence" to 0.9), gen = 0)
        override fun cancel() {}
        override fun limitedAfter(gen: Int) = gen >= 0 && this.gen > gen
    }

    @Test
    fun aSkippedQuestionIsNotReportedAsARequest() {
        val services = Collections.synchronizedList(ArrayList<ServiceCall>())
        val judge = object : Judge {
            override fun unfinished(text: String) = JevCall("{req}", 0, null, 0, "paused", null, JevLimit(Long.MAX_VALUE, null, true))
            override fun mistranslation(ja: String, zh: String) = unfinished(ja)
            override fun cancel() {}
        }
        // not paused() by the judge itself (a pause that began after the ASR looked): the question still goes out and is skipped
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, onService = { j, c -> if (j) services.add(c) })
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(false, p.holdHint(1, 2))
        p.stop(); p.awaitStopped()
        assertTrue(services.isEmpty())
    }

    @Test
    fun belowThresholdAnswersFinishAndFailedCallsGiveNoHint() {
        for ((pr, want) in listOf(0.3 to false, null to null)) {
            val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = FakeJudge(unfinishedP = pr))
            p.start()
            p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
            Thread.sleep(200)
            assertEquals(want, p.holdHint(1, 2))
            p.stop(); p.awaitStopped()
        }
    }

    @Test
    fun withoutAJudgeNothingChanges() {
        val out2 = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), FakeMt(1), { out2.add(it); done.countDown() })
        p.start(); p.submit(job(1, 3, true, 10))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf(true to 3), out2.map { it.isFinal to it.revision })
    }

    @Test
    fun theJevCheckIsTimedApartFromTheTranslationAndTheFinalNamesItsSourceRevision() {
        val out = runFinal(FakeJudge(choice = "ok", delayMs = 150))
        val f = out.last { it.isFinal }
        assertTrue("check ${f.timing.checkMs}", f.timing.checkMs >= 150)
        assertTrue("mt ${f.timing.mtMs}", f.timing.mtMs < 150)
        assertEquals(4, f.revision); assertEquals(3, f.sourceRevision) // raw ASR/MT/Jev events are logged as rev 3
    }

    @Test
    fun aRetranslationCountsAsTranslationTimeNotAsCheckTime() {
        val mt = FakeMt(120)
        val f = runFinal(FakeJudge(choice = "negation", delayMs = 50), mt = mt).last { it.isFinal }
        assertEquals(2, mt.calls.size)
        assertTrue("mt ${f.timing.mtMs}", f.timing.mtMs >= 240)
        assertTrue("check ${f.timing.checkMs}", f.timing.checkMs in 50..200)
    }

    @Test
    fun aJevCallCutShortByStopIsStillLogged() {
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val judge = FakeJudge(unfinishedP = 0.9, delayMs = 400)
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = judge, log = { events.add(it) })
        p.start()
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        Thread.sleep(100)
        p.stop(); p.awaitStopped()
        assertEquals("stopped", events.single { it["ev"] == "jev" }["action"])
    }

    @Test
    fun theLogTellsWhetherTheSegmenterUsedAHintOrItCameTooLate() {
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = FakeJudge(unfinishedP = 0.9, delayMs = 150), log = { events.add(it) })
        p.start()
        // seg 1: the segmenter reads the answer (twice: logged once)
        p.submit(AsrJob(1, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        p.holdHint(1, 2)
        // seg 2: finalized before Jev answered
        p.submit(AsrJob(2, 2, false, FloatArray(10), monoMs(), endOfSpeech = true))
        Thread.sleep(30)
        p.submit(AsrJob(2, 3, true, FloatArray(10), monoMs()))
        Thread.sleep(400)
        p.stop(); p.awaitStopped()
        val used = events.filter { it["ev"] == "hint_used" }
        assertEquals(listOf(1L to true), used.map { it["seg"] to it["hold"] })
        assertEquals("late", events.single { it["ev"] == "jev" && it["kind"] == "unfinished" && it["seg"] == 2L }["action"])
    }

    @Test
    fun whileAFinalIsWaitingNoDisplayDraftIsRecognizedOrTranslated() {
        val asr = CountingAsr(); val mt = GateMt()
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(2)
        val p = Pipeline(asr, mt, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 3, true, 10))
        assertTrue(mt.entered.await(2, TimeUnit.SECONDS)) // seg 1's final is being translated (slow phone)
        // drafts of the next sentence arrive while it waits, spaced so the ASR thread would pick each one up
        p.submit(job(2, 1, false, 21)); Thread.sleep(60)
        p.submit(job(2, 2, false, 22)); Thread.sleep(60)
        p.submit(job(2, 3, true, 23)); Thread.sleep(60)
        mt.gate.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf(10, 23), asr.seen)
        assertEquals(listOf("ja10", "ja23"), mt.order)
        assertEquals(listOf(1L to true, 2L to true), out.map { it.segmentId to it.isFinal })
    }

    @Test
    fun whenNothingIsWaitingDraftsAreStillShown() {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val shown = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), FakeMt(1), { out.add(it); if (!it.isFinal) shown.countDown() })
        p.start(); p.submit(job(1, 1, false, 10))
        assertTrue(shown.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
    }

    @Test
    fun aLaterPlainDraftDoesNotReplaceAQueuedSentenceEndCandidate() {
        val asr = GateAsr(); val judge = FakeJudge(unfinishedP = 0.9)
        val p = Pipeline(asr, FakeMt(1), {}, judge = judge)
        p.start()
        p.submit(job(1, 1, true, 5)) // keeps the ASR thread busy
        assertTrue(asr.entered.await(2, TimeUnit.SECONDS))
        p.submit(AsrJob(2, 4, false, FloatArray(24), monoMs(), endOfSpeech = true)) // Segmenter waits for this answer
        p.submit(job(2, 5, false, 25)) // speech went on 32 ms later: a plain draft of the same segment
        asr.gate.countDown()
        val deadline = monoMs() + 2000
        while (p.holdHint(2, 4) == null && monoMs() < deadline) Thread.sleep(5)
        p.stop(); p.awaitStopped()
        assertEquals(true, p.holdHint(2, 4))
        assertTrue(judge.asked.toString(), judge.asked.contains("u:ja24"))
    }

    @Test
    fun aFinalWithTheSameTextAsItsLastDraftReusesTheTranslation() {
        val mt = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val drafted = CountDownLatch(1); val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { out.add(it); if (it.isFinal) done.countDown() else drafted.countDown() })
        p.start()
        p.submit(job(1, 1, false, 10))
        assertTrue(drafted.await(2, TimeUnit.SECONDS))
        p.submit(job(1, 2, true, 10)) // speech ended: same audio, same recognized text
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, mt.calls.size)
        assertEquals("zh(ja10)", out.last { it.isFinal }.zh)
    }

    /** Returns null (cancel-like: nothing usable) for the first [failFirst] calls. */
    private class FlakyMt(var failFirst: Int) : Mt {
        val calls = Collections.synchronizedList(ArrayList<String>())
        override fun translate(prompt: String): MtResult? {
            calls.add(source(prompt))
            return if (calls.size <= failFirst) MtResult("", 'f', 0, 0) else MtResult("zh(${source(prompt)})", 'e', 1, 1)
        }
        override fun cancel() {}
    }

    @Test
    fun reuseIsPerSegmentAndNeverOfAFailure() {
        val mt = FlakyMt(failFirst = 1)
        val logs = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val finals = CountDownLatch(2)
        val drafted = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) finals.countDown() else drafted.countDown() }, log = { logs.add(it) })
        p.start()
        p.submit(job(1, 1, false, 10)) // fails: nothing shown, nothing cached
        Thread.sleep(100)
        p.submit(job(1, 2, true, 10)) // so the final translates again
        p.submit(job(2, 3, true, 10)) // same text in another segment: translated, not reused
        assertTrue(finals.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf("ja10", "ja10", "ja10"), mt.calls)
        assertTrue(logs.filter { it["ev"] == "mt" }.none { it["reused"] == true })
    }

    @Test
    fun aReusedFinalIsStillCheckedByJev() {
        val mt = FakeMt(1); val judge = FakeJudge(choice = "ok")
        val logs = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val drafted = CountDownLatch(1); val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.countDown() else drafted.countDown() }, judge = judge, checkTranslations = true, log = { logs.add(it) })
        p.start()
        p.submit(job(1, 1, false, 10)); assertTrue(drafted.await(2, TimeUnit.SECONDS))
        p.submit(job(1, 2, true, 10))
        assertTrue(done.await(2, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, mt.calls.size)
        assertTrue(judge.asked.toString(), judge.asked.contains("m:ja10|zh(ja10)"))
        val reused = logs.single { it["ev"] == "mt" && it["reused"] == true }
        assertEquals(1, reused["reused_from"]); assertEquals(2, reused["rev"])
    }

    @Test
    fun finalOnlySkipsDisplayDraftsButStillJudgesSentenceEnds() {
        val asr = CountingAsr(); val mt = FakeMt(1)
        val judge = FakeJudge(unfinishedP = 0.9)
        var live = false
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, mt, { out.add(it) }, judge = judge, drafts = { live })
        p.start()
        p.submit(job(1, 1, false, 11)) // display draft: skipped
        p.submit(AsrJob(1, 2, false, FloatArray(12), monoMs(), endOfSpeech = true)) // sentence-end candidate
        val deadline = monoMs() + 2000
        while (p.holdHint(1, 2) == null && monoMs() < deadline) Thread.sleep(5)
        assertEquals(true, p.holdHint(1, 2)) // Jev still got the candidate
        assertEquals(listOf(12), asr.seen)
        assertTrue(mt.calls.isEmpty()) // nothing translated just for display
        live = true // switched back to live while running: drafts resume
        val shown = CountDownLatch(1)
        p.submit(job(1, 3, false, 13))
        while (out.none { !it.isFinal } && monoMs() < deadline + 2000) Thread.sleep(5)
        p.stop(); p.awaitStopped()
        assertTrue(out.any { !it.isFinal && it.ja == "ja13" })
    }

    @Test
    fun finalOnlyWithChecksDisplaysNothingUntilTheCheckedFinal() {
        // The pipeline does publish the unchecked translation as an interim; "only finished sentences" hides it by
        // showing only finals, as CaptionService.refresh() does (board.snapshot().filter { it.isFinal }).
        val judge = GateJudge(choice = "negation")
        val mt = object : Mt {
            override fun translate(prompt: String) =
                MtResult(if (prompt.contains(FIX_NOTES["negation"]!!)) "checked" else "unchecked", 'e', 1, 1)
            override fun cancel() {}
        }
        val board = CaptionBoard(10); val done = CountDownLatch(1)
        fun shown() = synchronized(board) { board.snapshot().filter { it.isFinal }.map { it.zh } }
        val p = Pipeline(FakeAsr(), mt, { synchronized(board) { board.accept(it) }; if (it.isFinal) done.countDown() },
            judge = judge, checkTranslations = true, drafts = { false })
        p.start()
        try {
            p.submit(job(1, 1, false, 11))
            p.submit(job(1, 2, true, 10))
            assertTrue(judge.entered.await(2, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), shown()) // the unchecked translation is not displayed
            judge.gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { judge.gate.countDown(); p.stop(); p.awaitStopped() }
        assertEquals(listOf("checked"), shown())
    }

    /** A sentence-end candidate taken off the ASR queue (so its final cannot evict it), then its final; returns what the judge was asked. */
    private fun candidateThenFinal(judgeEnds: Boolean): List<String> {
        val judge = FakeJudge(unfinishedP = 0.9, choice = "ok")
        val recognized = CountDownLatch(1); val done = CountDownLatch(1)
        val asr = object : Asr {
            override fun recognize(samples: FloatArray) = "ja${samples.size}".also { if (samples.size == 12) recognized.countDown() }
        }
        val p = Pipeline(asr, FakeMt(1), { if (it.isFinal) done.countDown() }, judge = judge,
            judgeEnds = judgeEnds, checkTranslations = true)
        p.start()
        try {
            p.submit(AsrJob(1, 1, false, FloatArray(12), monoMs(), endOfSpeech = true))
            assertTrue(recognized.await(2, TimeUnit.SECONDS)) // taken off the queue: the final cannot evict it any more
            if (judgeEnds) {
                val deadline = monoMs() + 2000
                while (p.holdHint(1, 1) == null && monoMs() < deadline) Thread.sleep(5)
            } else Thread.sleep(50) // a time window, not proof: a wrongly started question would usually be asked by then
            p.submit(job(1, 2, true, 10))
            assertTrue(done.await(3, TimeUnit.SECONDS))
        } finally { p.stop(); p.awaitStopped() }
        return judge.asked.toList()
    }

    @Test
    fun checksWithoutSentenceEndJudgingNeverAskUnfinished() {
        val on = candidateThenFinal(judgeEnds = true) // control: the same candidate is asked about when enabled
        assertTrue(on.toString(), on.contains("u:ja12"))
        val off = candidateThenFinal(judgeEnds = false)
        assertTrue(off.toString(), off.none { it.startsWith("u:") })
        assertTrue(off.toString(), off.any { it.startsWith("m:ja10|") })
    }

    /** Recognitions with timings, by audio length; counts the calls (sizes). */
    private class TimedAsr(val by: Map<Int, List<Piece>>) : Asr {
        val calls = Collections.synchronizedList(ArrayList<Int>())
        override fun recognize(samples: FloatArray) = recognizeTimed(samples).text
        override fun recognizeTimed(samples: FloatArray): Recognition {
            calls += samples.size
            val p = by[samples.size] ?: emptyList()
            return Recognition(Pipeline.textOf(p), p)
        }
    }

    /** submits [jobs] one by one, each after the one before reached the screen; the finals' Japanese */
    private fun finalsOf(asr: Asr, jobs: List<AsrJob>, dedupeJoin: Boolean = false): Map<Long, String> {
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val p = Pipeline(asr, FakeMt(1), { out.add(it) }, dedupeJoin = dedupeJoin)
        p.start()
        try {
            for (j in jobs) {
                val seen = out.size
                p.submit(j)
                val t0 = monoMs(); while (out.size == seen && monoMs() - t0 < 2000) Thread.sleep(5)
            }
        } finally { p.stop(); p.awaitStopped() }
        return out.filter { it.isFinal }.associate { it.segmentId to it.ja }
    }

    /**
     * Real-log repro (session-20261016-074605 seg 4): the draft at 3.0 s had the request for cookies, the final at
     * 3.7 s only the words after the pause ("それねいそがしい"), and the caption lost the cookies. The final's words
     * start at 3.0 s: before them, the draft's words come back.
     */
    @Test
    fun wordsTheLongerRecognitionDroppedComeBackFromTheDraft() {
        val asr = TimedAsr(mapOf(
            48_128 to pieces("クッキーを何用意してくれていいですかって", 0f, 0.14f),
            59_904 to listOf(Piece("そ", 2.9f)) + pieces("れねいそがしい", 3.0f, 0.08f)))
        val f = finalsOf(asr, listOf(AsrJob(1, 1, false, FloatArray(48_128), monoMs()), AsrJob(1, 2, true, FloatArray(59_904), monoMs())))
        assertEquals("クッキーを何用意してくれていいですかってそれねいそがしい", f[1])
        // each audio recognized once: no second recognition to check a loss
        assertEquals(listOf(48_128, 59_904), asr.calls.toList())
    }

    /**
     * Replayed 15 min clip seg 41/42: the segmenter ended seg 41 at an earlier pause (2.53 s of its 4 s) and started
     * seg 42 with the rest; seg 42's first recognition dropped "お弁当ありがとう", which only seg 41's draft had.
     */
    @Test
    fun wordsDroppedFromAudioCarriedIntoTheNextSegmentComeBackFromThePreviousDraft() {
        val draft = pieces("これって大丈夫", 0f, 0.12f) + pieces("お弁当ありがとう", 2.8f, 0.09f)
        val asr = TimedAsr(mapOf(
            16_000 to pieces("これって", 0f, 0.12f),
            64_000 to draft,
            40_480 to pieces("これって大丈夫", 0f, 0.12f),
            39_520 to listOf(Piece("こ", 1.8f)) + pieces("れってさひとつききたいんだけど", 1.9f, 0.1f)))
        val audio = FloatArray(80_000) { it.toFloat() } // the segmenter's frames: seg 2 starts where seg 1's final ended
        // an earlier, shorter draft first: the audio compared is the longest draft's
        val f = finalsOf(asr, listOf(
            AsrJob(1, 1, false, audio.copyOfRange(0, 16_000), monoMs()),
            AsrJob(1, 2, false, audio.copyOfRange(0, 64_000), monoMs()),
            AsrJob(1, 3, true, audio.copyOfRange(0, 40_480), monoMs()),
            AsrJob(2, 1, true, audio.copyOfRange(40_480, 80_000), monoMs())))
        assertEquals("これって大丈夫", f[1])
        assertEquals("お弁当ありがとうこれってさひとつききたいんだけど", f[2])
        // other audio at the start of the next segment: nothing is carried
        val other = finalsOf(asr, listOf(
            AsrJob(1, 1, false, audio.copyOfRange(0, 64_000), monoMs()),
            AsrJob(1, 2, true, audio.copyOfRange(0, 40_480), monoMs()),
            AsrJob(2, 1, true, FloatArray(39_520), monoMs())))
        assertEquals("これってさひとつききたいんだけど", other[2])
    }

    @Test
    fun aShortDraftIsNotBroughtBack() {
        // 4 characters: below LOST_MIN_CHARS
        val asr = TimedAsr(mapOf(
            16_000 to pieces("ありがと", 0f, 0.15f),
            48_000 to listOf(Piece("そ", 1.9f)) + pieces("れでね", 2.0f, 0.1f)))
        val f = finalsOf(asr, listOf(AsrJob(1, 1, false, FloatArray(16_000), monoMs()), AsrJob(1, 2, true, FloatArray(48_000), monoMs())))
        assertEquals("それでね", f[1])
    }

    @Test
    fun anOlderReplacedDraftIsNotBroughtBack() {
        // rev 1 had "祖父の家に行った", rev 2 corrected it at the same times; the final drops the start: only rev 2 comes back
        val asr = TimedAsr(mapOf(
            16_000 to pieces("祖父の家に行った", 0f, 0.12f),
            24_000 to pieces("そうそう家に行った", 0f, 0.1f),
            48_000 to listOf(Piece("で", 1.9f)) + pieces("もね", 2.0f, 0.1f)))
        val f = finalsOf(asr, listOf(AsrJob(1, 1, false, FloatArray(16_000), monoMs()),
            AsrJob(1, 2, false, FloatArray(24_000), monoMs()), AsrJob(1, 3, true, FloatArray(48_000), monoMs())))
        assertEquals("そうそう家に行ったでもね", f[1])
    }

    @Test
    fun aNewerDraftWithoutTimingsStillReplacesAnOlderOne() {
        val asr = object : Asr {
            override fun recognize(samples: FloatArray) = recognizeTimed(samples).text
            override fun recognizeTimed(samples: FloatArray) = when (samples.size) {
                16_000 -> pieces("祖父の家に行った", 0f, 0.12f).let { Recognition(Pipeline.textOf(it), it) }
                32_000 -> Recognition("そうそう友達の家に行った", null)
                else -> (listOf(Piece("で", 1.9f)) + pieces("もね", 2.0f, 0.1f)).let { Recognition(Pipeline.textOf(it), it) }
            }
        }
        val f = finalsOf(asr, listOf(AsrJob(1, 1, false, FloatArray(16_000), monoMs()),
            AsrJob(1, 2, false, FloatArray(32_000), monoMs()), AsrJob(1, 3, true, FloatArray(48_000), monoMs())))
        assertEquals("でもね", f[1])
    }

    @Test
    fun aFinalShorterThanTheNewestDraftStillUsesThatDraft() {
        val asr = TimedAsr(mapOf(
            16_000 to pieces("祖父の家に行った", 0f, 0.1f),
            48_000 to pieces("そうそう友達の家に行った", 0f, 0.1f),
            40_000 to pieces("でもね", 2.0f, 0.1f)))
        val f = finalsOf(asr, listOf(AsrJob(1, 1, false, FloatArray(16_000), monoMs()),
            AsrJob(1, 2, false, FloatArray(48_000), monoMs()), AsrJob(1, 3, true, FloatArray(40_000), monoMs())))
        assertEquals("そうそう友達の家に行ったでもね", f[1])
    }

    /** a second loss keeps the words the first repair brought back: the history holds what was shown */
    @Test
    fun twoSuccessiveLossesKeepBothEarlierParts() {
        val asr = TimedAsr(mapOf(
            25_600 to pieces("スパチャありがとうございます", 0f, 0.1f),
            64_000 to listOf(Piece("い", 1.9f)) + pieces("や相当でかいと思うけど", 2.0f, 0.1f),
            96_000 to listOf(Piece("猫", 3.9f)) + pieces("が窓から飛び出した", 4.0f, 0.1f)))
        val f = finalsOf(asr, listOf(AsrJob(2, 1, false, FloatArray(25_600), monoMs()),
            AsrJob(2, 2, false, FloatArray(64_000), monoMs()), AsrJob(2, 3, true, FloatArray(96_000), monoMs())))
        assertEquals("スパチャありがとうございますいや相当でかいと思うけど猫が窓から飛び出した", f[2])
    }

    // --- settings changed while running (Setup) ---

    @Test
    fun aChangeDuringRecognitionAppliesFromTheNextSegment() {
        val asr = GateAsr()
        val old = FakeMt(1); val new = FakeMt(1)
        val out = Collections.synchronizedList(ArrayList<Caption>())
        val done = CountDownLatch(2)
        val p = Pipeline(asr, old, { out.add(it); if (it.isFinal) done.countDown() })
        p.start()
        p.submit(job(1, 1, true, 10))
        assertTrue(asr.entered.await(2, TimeUnit.SECONDS)) // segment 1 is being recognised with the old setup
        assertTrue(p.setSetup(Setup(new, version = 1)))
        asr.gate.countDown()
        p.submit(job(2, 1, true, 20))
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(listOf("ja10"), old.calls.map { source(it) })
        assertEquals(listOf("ja20"), new.calls.map { source(it) })
    }

    @Test
    fun aSegmentKeepsItsSetupForTheCheckAndTheRetranslation() {
        val oldMt = FakeMt(1); val newMt = FakeMt(1)
        val oldJudge = FakeJudge(choice = "negation", delayMs = 200); val newJudge = FakeJudge(choice = "negation")
        val events = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), oldMt, { if (it.isFinal) done.countDown() }, judge = oldJudge, checkTranslations = true,
            log = { events.add(it) })
        p.start()
        p.submit(job(1, 3, true, 10))
        // the first translation is done and the (slow) check is running when the settings change
        while (oldMt.calls.isEmpty()) Thread.sleep(5)
        assertTrue(p.setSetup(Setup(newMt, judge = newJudge, version = 1)))
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(2, oldMt.calls.size) // first try and the retranslation
        assertTrue(newMt.calls.isEmpty())
        assertEquals(1, oldJudge.asked.size); assertTrue(newJudge.asked.isEmpty())
        assertEquals(listOf(0, 0), events.filter { it["ev"] == "mt" }.map { it["setup"] })
    }

    @Test
    fun aKeyAddedWhileRunningStartsTheJudgeAndRemovingItPausesIt() {
        val judge = FakeJudge(unfinishedP = 0.1)
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = null, judgeEnds = true)
        p.start()
        fun ask(seg: Long): Boolean? {
            p.submit(AsrJob(seg, 1, false, FloatArray(10), monoMs(), endOfSpeech = true))
            val until = monoMs() + 2_000
            while (monoMs() < until) { p.holdHint(seg, 1)?.let { return it }; Thread.sleep(5) }
            return null
        }
        // started without a key: answered "finished" at once, nobody asked
        assertEquals(false, ask(1)); assertTrue(judge.asked.isEmpty())
        assertTrue(p.setSetup(Setup(FakeMt(1), judge = judge, version = 1)))
        assertEquals(false, ask(2)); assertEquals(1, judge.asked.size)
        assertTrue(p.setSetup(Setup(FakeMt(1), judge = null, version = 2)))
        assertEquals(false, ask(3)); assertEquals(1, judge.asked.size)
        p.stop(); p.awaitStopped()
    }

    @Test
    fun withoutSmartEndsTheUtteranceStartStillFixesItsSetup() {
        // smart sentence ends off, drafts off ("说完再出"): only the final reaches ASR, after the settings changed
        val old = FakeMt(1); val new = FakeMt(1)
        val done = CountDownLatch(1)
        val p = Pipeline(FakeAsr(), old, { if (it.isFinal) done.countDown() }, judgeEnds = false, drafts = { false })
        p.start()
        val seg = Segmenter({ p.submit(it) }, judged = p::judged)
        fun feed(speech: Boolean, ms: Int) = repeat(ms / 32) { seg.accept(FloatArray(512), if (speech) 0.9f else 0.1f, monoMs()) }
        feed(true, 1024)
        assertTrue(p.setSetup(Setup(new, version = 1))) // changed while the sentence is spoken
        feed(true, 512); feed(false, 608)
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, old.calls.size); assertTrue(new.calls.isEmpty())
    }

    @Test
    fun afterAForcedCutTheNextSentenceTakesTheSettingsOfWhenItStarts() {
        // a forced cut of nonstop speech leaves no tail; the next sentence starts only after a silence
        val old = FakeMt(1); val new = FakeMt(1)
        val done = CountDownLatch(2)
        val p = Pipeline(FakeAsr(), old, { if (it.isFinal) done.countDown() }, judgeEnds = false, drafts = { false })
        p.start()
        val seg = Segmenter({ p.submit(it) }, judged = p::judged)
        fun feed(speech: Boolean, ms: Int) = repeat(ms / 32) { seg.accept(FloatArray(512), if (speech) 0.9f else 0.1f, monoMs()) }
        feed(true, 7_008); feed(false, 640)
        assertTrue(p.setSetup(Setup(new, version = 1))) // changed in the silence, before the next sentence
        feed(true, 1_280); feed(false, 416)
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertEquals(1, old.calls.size); assertEquals(1, new.calls.size)
    }

    @Test
    fun judgedFollowsTheSetupTheUtteranceStartedWith() {
        val p = Pipeline(FakeAsr(), FakeMt(1), {}, judge = null, judgeEnds = true)
        p.start()
        assertFalse(p.judged(1)) // no key: segment 1 is cut as without Jev
        assertTrue(p.setSetup(Setup(FakeMt(1), judge = FakeJudge(), version = 1)))
        assertFalse(p.judged(1)) // fixed when it started
        assertTrue(p.judged(2))
        p.stop(); p.awaitStopped()
        val off = Pipeline(FakeAsr(), FakeMt(1), {}, judge = FakeJudge(), judgeEnds = false)
        assertFalse(off.judged(1)) // smart sentence ends off for the session
    }

    @Test
    fun aSegmentKeepsTheSetupItStartedWithWhileManyLaterOnesStart() {
        // ASR is blocked on segment 1 while the audio thread starts 12 more utterances and the settings change
        val asr = GateAsr()
        val old = FakeMt(1); val new = FakeMt(1)
        val done = CountDownLatch(1)
        val p = Pipeline(asr, old, { if (it.isFinal && it.segmentId == 1L) done.countDown() }, judgeEnds = true)
        p.start()
        assertFalse(p.judged(1)) // segment 1 starts (and takes the old setup) on the audio thread
        p.submit(job(1, 1, false, 10))
        assertTrue(asr.entered.await(2, TimeUnit.SECONDS))
        assertTrue(p.setSetup(Setup(new, version = 1)))
        for (i in 2L..13L) p.judged(i)
        p.submit(job(1, 2, true, 20))
        asr.gate.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS)); p.stop(); p.awaitStopped()
        assertTrue(old.calls.map { source(it) }.contains("ja20"))
        assertTrue(new.calls.none { source(it) == "ja20" })
    }

    @Test
    fun stopCancelsAnOldSetupStillTranslating() {
        val old = GateMt()
        val asrSeen = Collections.synchronizedList(ArrayList<Long>())
        val p = Pipeline(FakeAsr(), old, {}, log = { e -> if (e["ev"] == "asr") asrSeen.add(e["seg"] as Long) })
        p.start()
        p.submit(job(1, 1, true, 10))
        assertTrue(old.entered.await(2, TimeUnit.SECONDS))
        // many later segments with newer setups: the old one must still be cancelled on stop
        for (i in 1..12) { assertTrue(p.setSetup(Setup(FakeMt(1), version = i))); p.submit(job(1L + i, 1, true, 10 + i)) }
        // wait until the ASR thread has taken all of them (MT is still blocked on segment 1)
        val until = monoMs() + 2_000
        while (monoMs() < until && asrSeen.none { it == 13L }) Thread.sleep(5)
        assertTrue(asrSeen.contains(13L))
        p.stop()
        assertEquals(0L, old.gate.count) // GateMt.cancel opens the gate
        p.awaitStopped()
        assertFalse(p.setSetup(Setup(FakeMt(1), version = 99)))
    }

    @Test
    fun theContextCountFollowsTheSetupOfEachSegment() {
        val mt = FakeMt(1)
        val done = AtomicInteger()
        val p = Pipeline(FakeAsr(), mt, { if (it.isFinal) done.incrementAndGet() }, previousSentences = 0)
        p.start()
        for (i in 1..3) { p.submit(job(i.toLong(), 1, true, 10 + i)); while (done.get() < i) Thread.sleep(5) }
        assertTrue(p.setSetup(Setup(mt, previousSentences = 2, version = 1)))
        p.submit(job(4, 1, true, 14)); while (done.get() < 4) Thread.sleep(5)
        p.stop(); p.awaitStopped()
        // the first three had no context; the fourth gets the two sentences before it, kept while the count was 0
        assertFalse(mt.calls[2].contains("ja12"))
        assertTrue(mt.calls[3].contains("ja12") && mt.calls[3].contains("ja13") && !mt.calls[3].contains("ja11"))
    }
}

class CaptionBoardTest {
    private fun cap(seg: Long, rev: Int, final: Boolean, zh: String = "z$seg.$rev") =
        Caption(seg, rev, final, "", zh, Timing(0, 0, 0, 0, 0, 0, 0))

    @Test
    fun revisionsOnlyMoveForward() {
        val b = CaptionBoard()
        assertTrue(b.accept(cap(1, 2, false)))
        assertFalse(b.accept(cap(1, 1, false)))
        assertTrue(b.accept(cap(1, 3, true)))
        assertFalse(b.accept(cap(1, 4, false)))
        assertEquals("z1.3", b.snapshot().single().zh)
    }

    @Test
    fun aDraftNeverPushesOutAFinalAndStaleOldDraftsAreIgnored() {
        val b = CaptionBoard(minFinals = 2, maxChars = 0)
        b.accept(cap(1, 1, true)); b.accept(cap(2, 1, true)); b.accept(cap(3, 1, false))
        // the draft is an extra line: it does not push out a finished sentence
        assertEquals(listOf(1L, 2L, 3L), b.snapshot().map { it.segmentId })
        assertFalse(b.accept(cap(1, 5, false)))
        b.accept(cap(3, 2, true))
        assertEquals(listOf(2L, 3L), b.snapshot().map { it.segmentId })
    }

    private fun zh(n: Int) = "字".repeat(n)

    /** User rule: an old sentence leaves only when there are more than N sentences AND more than M characters. */
    @Test
    fun keepsOldSentencesUntilBothTheSentenceAndTheCharacterLimitAreExceeded() {
        val b = CaptionBoard(minFinals = 2, maxChars = 100)
        // more than 2 sentences but only 90 characters: all stay
        for (i in 1L..3L) b.accept(cap(i, 1, true, zh = zh(30)))
        assertEquals(listOf(1L, 2L, 3L), b.snapshot().map { it.segmentId })
        // 120 characters and 4 sentences: the oldest goes, back to 90
        b.accept(cap(4, 1, true, zh = zh(30)))
        assertEquals(listOf(2L, 3L, 4L), b.snapshot().map { it.segmentId })
    }

    @Test
    fun exactlyAtTheLimitsNothingIsRemovedAndTwoLongSentencesAlwaysStay() {
        val b = CaptionBoard(minFinals = 2, maxChars = 100)
        b.accept(cap(1, 1, true, zh = zh(50))); b.accept(cap(2, 1, true, zh = zh(50)))
        assertEquals(2, b.snapshot().size) // exactly 100: not over
        // over the characters but not over the sentence count: both long sentences stay
        b.accept(cap(3, 1, true, zh = zh(300)))
        assertEquals(listOf(2L, 3L), b.snapshot().map { it.segmentId })
    }

    @Test
    fun removesSeveralOldSentencesAtOnceAndCountsCodePointsOfFinalsOnly() {
        val b = CaptionBoard(minFinals = 1, maxChars = 10)
        for (i in 1L..5L) b.accept(cap(i, 1, true, zh = "😀😀"))  // 2 code points each, 10 in total
        assertEquals(5, b.snapshot().size)
        b.accept(cap(6, 1, false, zh = zh(50))) // a long draft is not counted
        assertEquals(6, b.snapshot().size)
        b.accept(cap(6, 2, true, zh = zh(9)))    // 19 characters: drop oldest until ≤ 10 → only 6 (9) is left
        assertEquals(listOf(6L), b.snapshot().map { it.segmentId })
    }

    @Test
    fun loweringTheLimitsWhileRunningTrimsAtOnceAndOldFinalsDoNotComeBack() {
        val b = CaptionBoard(minFinals = 2, maxChars = 100)
        for (i in 1L..4L) b.accept(cap(i, 1, true, zh = zh(20)))
        assertEquals(4, b.snapshot().size)
        assertTrue(b.setLimits(2, 30))
        assertEquals(listOf(3L, 4L), b.snapshot().map { it.segmentId })
        assertFalse(b.setLimits(2, 30))
        // a removed sentence's late final / draft does not come back
        assertFalse(b.accept(cap(1, 2, true)))
        assertFalse(b.accept(cap(2, 2, false)))
    }

    /** User report: a sentence read as finished was later a different sentence. Finals never change on screen. */
    @Test
    fun aShownFinalNeverChangesWhileLaterSpeechComesIn() {
        val b = CaptionBoard(minFinals = 2, maxChars = 0)
        b.accept(cap(1, 3, true, zh = "顺便一提啊……"))
        for (rev in 1..6) b.accept(cap(2, rev, false, zh = "草稿$rev"))
        assertEquals(listOf("顺便一提啊……", "草稿6"), b.snapshot().map { it.zh })
        assertEquals(listOf(true, false), b.snapshot().map { it.isFinal })
        b.accept(cap(2, 7, true, zh = "定稿2"))
        assertEquals(listOf("顺便一提啊……", "定稿2"), b.snapshot().map { it.zh })
    }

    @Test
    fun aDraftOfALaterSegmentReplacesTheDraftLineButNotFinals() {
        val b = CaptionBoard()
        b.accept(cap(1, 1, true)); b.accept(cap(2, 1, false)); b.accept(cap(3, 1, false))
        assertEquals(listOf(1L, 3L), b.snapshot().map { it.segmentId })
        // a late draft of segment 2 cannot come back once 3 is the draft
        assertFalse(b.accept(cap(2, 2, false)))
        // segment 2's final still lands, in order, and keeps the newer draft line
        assertTrue(b.accept(cap(2, 3, true)))
        assertEquals(listOf(1L, 2L, 3L), b.snapshot().map { it.segmentId })
        assertEquals(listOf(true, true, false), b.snapshot().map { it.isFinal })
    }

    @Test
    fun emptyFinalRemovesDraftLine() {
        val b = CaptionBoard()
        b.accept(cap(1, 1, false)); b.accept(cap(1, 2, true, zh = ""))
        assertTrue(b.snapshot().isEmpty())
    }
}
