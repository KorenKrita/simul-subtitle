package io.github.korenkrita.simulsub

import java.util.concurrent.LinkedBlockingDeque

/** Monotonic milliseconds; every timestamp in the pipeline uses this clock. */
fun monoMs(): Long = System.nanoTime() / 1_000_000

/** A chunk of Japanese speech; draft = the speaker is still talking. */
data class AsrJob(
    val segmentId: Long,
    val revision: Int,
    val isFinal: Boolean,
    val samples: FloatArray,
    /** [monoMs] when the last sample of [samples] was captured */
    val audioEndAt: Long,
    /** [audioEndAt] is the read time, not a HAL capture timestamp (underestimates latency) */
    val timeEstimated: Boolean = false,
    /** a draft cut at a pause: everything the speaker said before it; the segmenter asks whether it is finished */
    val endOfSpeech: Boolean = false,
)

data class Caption(
    val segmentId: Long,
    val revision: Int,
    val isFinal: Boolean,
    val ja: String,
    val zh: String,
    val timing: Timing,
    /** [monoMs] capture time of the last sample, so the UI can measure capture → text update itself */
    val audioEndAt: Long = 0,
    /** see [AsrJob.timeEstimated] */
    val timeEstimated: Boolean = false,
    /** the ASR/MT revision this caption comes from; a checked final is shown as revision + 1 but logged under this */
    val sourceRevision: Int = revision,
)

data class Timing(
    val audioMs: Long,
    /** from the last sample of the chunk until the segmenter handed it over (end-of-speech wait) */
    val segmentWaitMs: Long,
    val asrQueueMs: Long,
    val asrMs: Long,
    val mtQueueMs: Long,
    /** the translation(s) only; a Jev check and its retry are in [checkMs] */
    val mtMs: Long,
    /** from the last captured sample until MT finished (UI delivery is measured separately by the app) */
    val mtDoneMs: Long,
    /** the Jev mistranslation call of a final (0 without Jev); a retranslation it asked for is in [mtMs] */
    val checkMs: Long = 0,
)

interface Asr {
    fun recognize(samples: FloatArray): String
    /** The text with when each piece of it was said; an engine that cannot tell gives no pieces. */
    fun recognizeTimed(samples: FloatArray): Recognition = Recognition(recognize(samples), null)
}

/** A piece of recognized text and when it starts, in seconds from the start of the recognized audio. */
class Piece(val text: String, val t: Float)

/** [pieces] joined are [text]; null: no timings (the loss repair is then off for this recognition). */
class Recognition(val text: String, val pieces: List<Piece>?) {
    companion object {
        /**
         * ReazonSpeech drops the first word when speech starts right at sample 0 (measured: すみません/そんな/まっすぐ/
         * おじいさん lost): the engines recognize audio with this much silence on both ends ([padded]), and the loss
         * repair reads its timings knowing it.
         */
        const val PAD_S = 0.3f
        private const val PAD = (PAD_S * 16_000).toInt()
        /** 16 kHz [samples] with [PAD_S] of silence on both ends */
        fun padded(samples: FloatArray) = FloatArray(samples.size + 2 * PAD).also { System.arraycopy(samples, 0, it, PAD, samples.size) }

        /**
         * sherpa-onnx's [tokens] and [times] mapped onto its [text]; [pad] seconds of silence were put before the
         * audio. A Japanese token is byte-level BPE (one character per UTF-8 byte of the text, a character can be split
         * over two tokens) and an English one is plain text; both start with "▁" where a space may be. A token that
         * does not fit the text gives no pieces. The first token comes at frame 0 whatever its real time (it would put
         * a pause after it that is not there), so it is given a time just before the second one.
         */
        fun of(text: String, tokens: Array<String>, times: FloatArray, pad: Float): Recognition {
            val bytes = text.toByteArray(Charsets.UTF_8)
            // the character where each byte's code point starts: a byte offset inside a character rounds down to it,
            // so a token with only the start of a character gets none of it
            val charAt = IntArray(bytes.size + 1)
            var b = 0
            var ch = 0
            while (ch < text.length) {
                val cp = text.codePointAt(ch)
                repeat(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size) { charAt[b++] = ch }
                ch += Character.charCount(cp)
            }
            charAt[bytes.size] = text.length
            if (tokens.size != times.size) return Recognition(text, null)
            val pieces = ArrayList<Piece>(tokens.size)
            var pos = 0
            var start = 0
            // a piece per whole character run, at the time of the token its first byte came in
            var startT = Float.NaN
            for ((i, tok) in tokens.withIndex()) {
                if (tok.startsWith("▁") && pos < bytes.size && bytes[pos] == ' '.code.toByte()) pos++
                pos += tok.length - tok.count { it == '▁' }
                if (pos > bytes.size) return Recognition(text, null)
                if (startT.isNaN()) startT = times[i]
                val end = charAt[pos]
                if (end == start) continue
                pieces += Piece(text.substring(start, end), startT - pad)
                start = end
                // the next character already began in this token (its first bytes are here)
                startT = if (pos < bytes.size && (bytes[pos].toInt() and 0xC0) == 0x80) times[i] else Float.NaN
            }
            if (pos != bytes.size) return Recognition(text, null)
            if (pieces.size > 1 && pieces[0].t <= -pad + 0.05f) pieces[0] = Piece(pieces[0].text, pieces[1].t - 0.1f)
            return Recognition(text, pieces)
        }
    }
}

interface Mt {
    /** One generation for a complete prompt; null when cancelled (only used on stop) or when nothing ran. */
    fun translate(prompt: String): MtResult?
    fun cancel()
}

/**
 * What a segment is translated with: the translator, the Jev client and the prompt settings. Immutable; the
 * session swaps in a new one when the user changes a setting while subtitles run ([Pipeline.setSetup]).
 * A segment takes the setup current when its first job reaches the ASR thread and keeps it to the end, so its
 * drafts, its final, the check and a retranslation never mix two settings. [version] tells setups apart (it
 * is part of the translation reuse key, also when two setups share one translator instance).
 */
class Setup(
    val mt: Mt,
    val scene: Scene = Scene.GENERAL,
    /** null = auto (Japanese or English) */
    val source: Lang? = Lang.JA,
    val target: Target = Target.ZH,
    /** null: no Jev for this setup (not configured, or paused because its key is missing) */
    val judge: Judge? = null,
    val dedupeJoin: Boolean = false,
    /** finished sentences given as context; 0 = none */
    val previousSentences: Int = Pipeline.PREVIOUS_SENTENCES,
    val version: Int = 0,
)

/**
 * The outcome of one real request to the translator or Jev (not a reused translation), for the overlay's lights.
 * [http] 0 = no HTTP status (network failure, cancelled); [limit]: Jev is paused after a 429; [setup] its version.
 * [limited]: the result came with a 429 or while one was on (its answer, also a valid one, not used); its limit's end
 * may not be known yet ([limit] null) and is read from the service when shown.
 */
class ServiceCall(val ok: Boolean, val http: Int, val error: String?, val limit: JevLimit?, val setup: Int,
                  val limited: Boolean = limit != null || http == 429)

/**
 * Two worker threads (ASR, MT) connected by small queues.
 *
 * "Draft then correct" contract:
 * - Finals are processed in order and never dropped.
 * - Drafts are disposable: a newer job for the same segment replaces a draft that has not started yet.
 *   A running draft is never pre-empted, so a slow phone still shows a draft while the speaker talks.
 * - The UI ([CaptionBoard]) only moves forward per segment and freezes a segment after its final.
 * - Overload is explicit: when un-translated finals hold more than [maxPendingAudioMs] of speech,
 *   [onOverload] fires once and new jobs are refused; nothing is dropped silently.
 * - [stop] is non-blocking; call [awaitStopped] (off the main thread) before releasing [asr]/[mt].
 */
class Pipeline(
    private val asr: Asr,
    mt: Mt,
    private val onCaption: (Caption) -> Unit,
    private val onBacklog: (pendingFinals: Int) -> Unit = {},
    private val onOverload: () -> Unit = {},
    /** A worker threw; the pipeline refuses new jobs from now on. Called on the worker thread. */
    private val onFailure: (Throwable) -> Unit = {},
    private val maxPendingAudioMs: Long = 30_000,
    private val clock: () -> Long = ::monoMs,
    private val sampleRate: Int = 16_000,
    scene: Scene = Scene.GENERAL,
    /** null = auto (Japanese or English); only the translator and the join of kept drafts use it */
    source: Lang? = Lang.JA,
    target: Target = Target.ZH,
    /**
     * Optional cloud judge (Jev). With it: endOfSpeech drafts are asked "unfinished?" on a separate thread and the
     * answer is offered to the segmenter through [holdHint]; every final translation is checked once and, for the
     * error classes in [FIX_NOTES], translated again with the matching note. Any failure keeps the first translation.
     */
    private val judge: Judge? = null,
    /** Ask [judge] at sentence-end candidates (the "unfinished?" question). Default on, as before the check switch. */
    private val judgeEnds: Boolean = true,
    /**
     * Where a kept draft and the recognition of the audio after it are joined, drop a repeat of the kept end at the
     * start of the new part (see [repairLoss]). Off by default: a repeat the speaker really said may be dropped.
     */
    dedupeJoin: Boolean = false,
    /** Finished sentences given to the translator as context (the prompt's 【前文】); 0 = none. */
    previousSentences: Int = PREVIOUS_SENTENCES,
    /** Also ask [judge] to check every final translation (off by default: little gain in logs, adds time per final). */
    private val checkTranslations: Boolean = false,
    /** [Setup.version] of the constructor's settings (the first setup) */
    setupVersion: Int = 0,
    /** Raw session events (asr / mt / jev) for the session log. Called on worker threads; must not block. */
    private val log: (Map<String, Any?>) -> Unit = {},
    /** Read live: false = "only finished sentences", display drafts are not recognized or translated at all. */
    private val drafts: () -> Boolean = { true },
    /** After each real request to the translator (jev false) or Jev (true), on a worker thread; must not block. */
    private val onService: (jev: Boolean, call: ServiceCall) -> Unit = { _, _ -> },
) {
    private class Queued(val job: AsrJob, val submittedAt: Long)
    private class MtJob(val q: Queued, val ja: String, val asrStartAt: Long, val asrEndAt: Long, val setup: Setup)

    /** The setup new segments take; see [Setup]. The constructor's settings are the first one. */
    @Volatile private var current = Setup(mt, scene, source, target, judge, dedupeJoin, previousSentences, setupVersion)
    /** Every setup a pipeline worker may still call (current + those of segments in flight), so [stop] cancels all. */
    private val live: MutableSet<Setup> = java.util.concurrent.ConcurrentHashMap.newKeySet<Setup>().apply { add(current) }
    /** The setup each recent segment took (ASR thread writes, MT / check threads read through their jobs). */
    private val segSetup = java.util.concurrent.ConcurrentHashMap<Long, Setup>()

    /**
     * New segments use [s] from now on; a segment already started keeps its own. Settings fixed for the session
     * ([judgeEnds], [checkTranslations], the drafts switch) are not part of a setup. Thread-safe; returns false
     * once the pipeline is stopped (the caller then releases [s] itself). [beforeVisible] runs once it is sure to be
     * taken, before any worker can use it (so anything it queues comes before the results of [s]).
     */
    fun setSetup(s: Setup, beforeVisible: () -> Unit = {}): Boolean {
        synchronized(lock) {
            if (!running) return false
            beforeVisible()
            live.add(s)
            current = s
        }
        if (!running) { s.mt.cancel(); s.judge?.cancel() } // stop() ran meanwhile and did not see it
        return true
    }
    /**
     * The setup of [seg], fixed by whichever asks first: the audio thread when the utterance starts ([judged]) or the
     * ASR thread at its first recognition. An entry stays until the segment's final is delivered ([forget]); [live]
     * keeps every setup of the session, since a slow MT or check may still use an old one when stop() cancels it.
     */
    private fun setupOf(seg: Long): Setup = segSetup.getOrPut(seg) { current }
    /** The final of [seg] was delivered: nothing asks for its setup any more (its jobs carry their own). */
    private fun forget(seg: Long) {
        segSetup.remove(seg)
        // a segment that ended without a final (empty, dropped by stop) would otherwise stay: keep the table bounded
        if (segSetup.size > 64) segSetup.keys.removeIf { it < seg - 32 }
        // the draft sent for this segment is done with it (see worthSendingDraft); a segment that never got a final
        // stays behind, so this table is bounded too
        synchronized(lock) {
            sentDraft.remove(seg)
            if (sentDraft.size > 64) sentDraft.keys.removeIf { it < seg - 32 }
        }
        skipNextDraft.removeIf { it <= seg }
    }

    /**
     * For the [Segmenter] when an utterance starts (audio thread, never blocks): true = it may wait for Jev answers.
     * Fixes the segment's setup now (also without smart sentence ends), so its sentence ends and its translation use
     * the same one and a change while it is spoken, or while ASR is behind, applies from the next utterance.
     */
    fun judged(segmentId: Long): Boolean {
        val j = setupOf(segmentId).judge // setup fixed first, always
        // limited by the service (429): cut by pauses as without Jev, nothing is asked until the pause ends
        return j != null && judgeEnds && j.paused() == null
    }

    /** Jev answers for endOfSpeech drafts, by (segment, revision); true = unfinished, with the answer's limit generation. */
    private val hints = java.util.concurrent.ConcurrentHashMap<Pair<Long, Int>, Pair<Boolean, Int>>()
    /** The newest endOfSpeech draft waiting for Jev; an older one that has not started is replaced. */
    private val pendingJudge = java.util.concurrent.atomic.AtomicReference<Triple<AsrJob, String, Setup>?>(null)

    /** For [Segmenter.holdHint]: never blocks; null until Jev answered for exactly this revision. */
    fun holdHint(segmentId: Long, revision: Int): Boolean? {
        // the segment's own judge limited since this answer's request went out (also a pause already over), or now:
        // finished, over an earlier "hold"
        val (hold, gen) = hints[segmentId to revision] ?: (null to -1)
        val j = segSetup[segmentId]?.judge
        val h = if (j != null && (j.paused() != null || j.limitedAfter(gen))) false else hold ?: return null
        // the segmenter asks on every frame: log only the first read, which is when the answer took effect
        if (hintsUsed.add(segmentId to revision)) log(mapOf("ev" to "hint_used", "seg" to segmentId, "rev" to revision, "hold" to h))
        return h
    }
    private val hintsUsed: MutableSet<Pair<Long, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** newest segment handed over as final: a Jev answer for it or an older one came too late to matter */
    @Volatile private var lastFinalSeg = -1L

    // Shared lock for both queues so pending-final accounting is consistent.
    private val lock = Object()
    private val asrQueue = LinkedBlockingDeque<Queued>()
    private val mtQueue = LinkedBlockingDeque<MtJob>()
    private var pendingFinalAudioMs = 0L
    /** finals taken off a queue and not yet delivered (guarded by [lock]) */
    private var inFlightFinals = 0
    @Volatile private var running = false
    @Volatile private var overloaded = false
    @Volatile private var failed = false
    private var threads: List<Thread> = emptyList()

    fun start() {
        check(threads.isEmpty()) { "Pipeline is single-use" }
        running = true
        threads = listOfNotNull(
            Thread(guarded(::asrLoop), "jpsub-asr"),
            Thread(guarded(::mtLoop), "jpsub-mt"),
            // The session switches decide whether these run at all (both are fixed for the session); a setup without
            // a judge (its key is missing for now) simply finds nothing to ask and pauses the feature.
            if (judgeEnds) Thread(guarded(::judgeLoop), "jpsub-jev") else null,
            if (checkTranslations) Thread(guarded(::checkLoop), "jpsub-check") else null,
        ).onEach { it.start() }
    }

    /** Non-blocking. Results produced after this call are discarded. */
    fun stop() {
        synchronized(lock) { running = false }
        live.forEach { it.mt.cancel(); it.judge?.cancel() }
        threads.forEach { it.interrupt() }
    }

    /** Blocks until both workers have exited (native calls finish on their own; MT is cancelled). */
    fun awaitStopped() = threads.forEach { it.join() }

    /**
     * Stops accepting jobs, drops queued drafts, and waits up to [timeoutMs] for the accepted finals to be
     * delivered; returns early if the pipeline is stopped or fails. Always ends with the pipeline stopped
     * and its workers joined, so nothing is delivered after it returns. Not for the worker threads.
     * @return the number of accepted finals that were not delivered (0 = fully drained).
     */
    fun drainFinals(timeoutMs: Long): Int {
        synchronized(lock) {
            overloaded = true
            asrQueue.removeIf { !it.job.isFinal }
            mtQueue.removeIf { !it.q.job.isFinal }
        }
        val deadline = clock() + timeoutMs
        while (running && !failed && clock() < deadline && synchronized(lock) { pendingFinals() + inFlightFinals } > 0) {
            Thread.sleep(20)
        }
        stop(); awaitStopped()
        // in-flight finals are decremented only after onCaption returns, so after the join this count is exact
        return synchronized(lock) { pendingFinals() + inFlightFinals }
    }

    /** Called from the audio thread. Never blocks. @return false if refused (stopped or overloaded). */
    fun submit(job: AsrJob): Boolean {
        val backlog: Int
        synchronized(lock) {
            if (!running || overloaded || failed) return false
            // A sentence-end candidate is a question the Segmenter is waiting on: a plain draft must not evict it,
            // only a newer candidate or the final (which ends the wait) may.
            val evicts = job.isFinal || job.endOfSpeech
            asrQueue.removeIf { it.job.segmentId == job.segmentId && !it.job.isFinal && (evicts || !it.job.endOfSpeech) }
            if (job.isFinal) {
                if (job.segmentId > lastFinalSeg) lastFinalSeg = job.segmentId
                pendingFinalAudioMs += audioMs(job)
                if (pendingFinalAudioMs > maxPendingAudioMs) overloaded = true
            }
            asrQueue.addLast(Queued(job, clock()))
            backlog = pendingFinals()
        }
        onBacklog(backlog)
        if (overloaded) onOverload()
        return true
    }

    /** Runs a worker body; any exception puts the pipeline into the failed state instead of killing the process. */
    private fun guarded(body: () -> Unit) = Runnable {
        try {
            body()
        } catch (e: InterruptedException) {
            // stop() interrupts the workers after clearing `running`; any other interruption is a failure
            if (running) { failed = true; onFailure(e) }
        } catch (t: Throwable) {
            failed = true
            if (running) onFailure(t)
        }
    }

    /** Polls [q]; a final that leaves the queue is counted in [inFlightFinals] atomically, so drain never misses it. */
    private fun <T> take(q: LinkedBlockingDeque<T>, isFinal: (T) -> Boolean): T? {
        synchronized(lock) {
            val x = q.pollFirst() ?: return@synchronized
            if (isFinal(x)) inFlightFinals++
            return x
        }
        Thread.sleep(10)
        return null
    }

    /** A draft as shown: its audio's samples (see [segAudio]), its text and that text in timed pieces (after any repair; null: no timings). */
    private class Shown(val size: Int, val text: String, val pieces: List<Piece>?)
    /** Per segment, its drafts as shown, newest last (see [carried] for the first); asr thread only. */
    private val segDrafts = HashMap<Long, MutableList<Shown>>()
    /** Per segment, its longest draft's audio: every draft starts at the segment start, so each is a prefix of it; asr thread only. */
    private val segAudio = HashMap<Long, FloatArray>()
    /** Samples in each segment's final; asr thread only. */
    private val finalSamples = HashMap<Long, Int>()
    /** The draft the translator last took per segment (see [worthSendingDraft]); guarded by [lock]. */
    private val sentDraft = HashMap<Long, String>()
    /**
     * Segments whose last draft was not worth translating: their next draft is not recognized at all (a final or a
     * sentence-end candidate always is). Speech that barely grew in one draft interval rarely grows enough in the
     * next, and every draft recognizes the whole segment again. Replayed on the 15 min clip: 21% fewer draft
     * recognitions (25% of their time), draft translations 495 -> 445. Asr thread only.
     */
    private val skipNextDraft = HashSet<Long>()

    /**
     * ReazonSpeech sometimes drops everything before an internal pause when the same utterance is recognized again
     * with more audio (measured: "スパチありがとうございま" at 1.6 s became "いや相当でかいと思うけど" at 4.8 s; the audio is
     * an exact prefix, fp32 does the same). The timings show it: the new result has no words where the newest draft
     * had some, and [repairLoss] puts the draft's words back there. Replayed on a 15 min VTuber clip (every ASR job
     * recognized again offline): drafts' words missing from every final went from 10 segments (124 characters) with
     * the check this replaces (recognize the audio after the draft on its own, compare) to 4 (47), of which 3 only
     * differ in spelling; 21 of 199 finals changed, about 17 of them by words brought back.
     */
    private fun recognizeKeepingLostDraft(job: AsrJob, setup: Setup): String {
        val r = asr.recognizeTimed(job.samples)
        val whole = r.text.trim()
        val hist = segDrafts.getOrPut(job.segmentId) { carried(job) }
        segDrafts.keys.removeIf { it < job.segmentId - 4 }
        segAudio.keys.removeIf { it < job.segmentId - 4 }
        finalSamples.keys.removeIf { it < job.segmentId - 4 }
        // only the newest draft counts, also one without timings (then nothing is repaired): an older one it already
        // replaced is not brought back. Its words past the end of this audio are not (a final may end before a draft).
        val audioSec = job.samples.size.toFloat() / sampleRate
        val last = hist.lastOrNull { it.text.count { c -> !c.isWhitespace() } >= LOST_MIN_CHARS }
        val kept = last?.pieces?.filter { it.t < audioSec }
        val fixed = if (r.pieces == null || kept == null) null
            else repairLoss(r.pieces, kept, audioSec, setup.dedupeJoin)
        val text = fixed?.let { textOf(it) } ?: whole
        if (fixed != null) log(mapOf("ev" to "asr_keep", "seg" to job.segmentId, "rev" to job.revision, "whole" to whole,
            "kept" to textOf(kept!!), "carried" to (last!!.size == 0), "text" to text))
        // the history holds what was shown, so a second loss keeps the words an earlier repair brought back
        if (job.isFinal) finalSamples[job.segmentId] = job.samples.size
        else {
            hist += Shown(job.samples.size, text, fixed ?: r.pieces)
            if (job.samples.size > (segAudio[job.segmentId]?.size ?: 0)) segAudio[job.segmentId] = job.samples
        }
        return text
    }

    /**
     * The [Segmenter] may end a segment at an earlier pause and start the next one with the rest of its audio. The
     * first recognition of that audio can drop its start too, with no draft of the new segment to bring it back: the
     * previous segment's last draft, from that pause on and shifted to the new start, is the new segment's first
     * (only when the new audio really starts with the previous draft's rest). Replayed: brought back 2 sentences.
     */
    private fun carried(job: AsrJob): MutableList<Shown> {
        val list = ArrayList<Shown>()
        val cut = finalSamples[job.segmentId - 1] ?: return list
        // the last draft with audio past the cut (a later, shorter one may follow a forced cut)
        val prev = segDrafts[job.segmentId - 1]?.lastOrNull { it.size >= cut + sampleRate / 10 } ?: return list
        val pieces = prev.pieces ?: return list
        val audio = segAudio[job.segmentId - 1] ?: return list
        val k = minOf(prev.size - cut, job.samples.size)
        if (cut <= 0 || k < sampleRate / 10) return list
        for (i in 0 until k) if (audio[cut + i] != job.samples[i]) return list
        val off = cut.toFloat() / sampleRate
        // size 0: never the previous draft of a following segment
        val rest = pieces.filter { it.t >= off + 0.05f }.map { Piece(it.text, it.t - off) }
        list += Shown(0, textOf(rest), rest)
        return list
    }

    private fun asrLoop() {
        while (running && !failed) {
            val q = take(asrQueue) { it.job.isFinal } ?: continue
            if (!q.job.isFinal && !q.job.endOfSpeech && skipDisplayDraft()) continue
            if (!q.job.isFinal && !q.job.endOfSpeech && skipNextDraft.remove(q.job.segmentId)) continue
            // the segment's settings are fixed before its first recognition, so a change during it applies to the next one
            val setup = setupOf(q.job.segmentId)
            val start = clock()
            val ja = recognizeKeepingLostDraft(q.job, setup)
            val end = clock()
            if (!running) return
            val job = q.job
            log(mapOf("ev" to "asr", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal,
                "eos" to job.endOfSpeech, "audio_ms" to audioMs(job), "ms" to end - start, "text" to ja))
            // judgeEnds alone: a session may start without a key and get one later (the setup then has a judge)
            if (judgeEnds && job.endOfSpeech) {
                // nothing recognised = nothing to wait for; otherwise ask, replacing an older question not yet sent.
                // No judge in this segment's setup (key missing for now) or one the service limited: no question,
                // the segmenter cuts at once as without Jev instead of waiting for an answer that never comes
                if (ja.isEmpty() || setup.judge == null || setup.judge.paused() != null) hints[job.segmentId to job.revision] = false to -1
                else pendingJudge.set(Triple(job, ja, setup))
            }
            synchronized(lock) {
                // A draft that already ran is kept even if a newer draft is queued: on a slow phone every
                // draft would otherwise be superseded before it reaches MT and nothing would be shown.
                if (!q.job.isFinal && ja.isEmpty()) return@synchronized
                // a sentence-end candidate was recognized for Jev; it is only translated if drafts are wanted now
                if (!q.job.isFinal && !drafts()) return@synchronized
                // A draft that barely grew since the one already sent would translate to nearly the same line: skip it
                // and let the newer text ride along with the next one (see DRAFT_MIN_NEW_CHARS). Only here, after the
                // checks above and before the queue is touched: a skipped draft must not drop the one already queued,
                // and a draft that was never translated must not become what the next one is measured against.
                // A final always goes through.
                // the next plain draft is skipped only after a plain one (a sentence-end candidate is recognized anyway)
                if (!q.job.isFinal && !worthSendingDraft(q.job.segmentId, ja)) {
                    if (!q.job.endOfSpeech) skipNextDraft += q.job.segmentId
                    return@synchronized
                }
                skipNextDraft.remove(q.job.segmentId)
                mtQueue.removeIf { it.q.job.segmentId == q.job.segmentId && !it.q.job.isFinal }
                mtQueue.addLast(MtJob(q, ja, start, end, setup))
                if (q.job.isFinal) inFlightFinals-- // now counted by mtQueue
            }
            // the final carries its setup on (MtJob): the segment needs no lookup any more
            if (q.job.isFinal) forget(q.job.segmentId)
        }
    }

    /**
     * Whether this draft is worth a translation call (see [DRAFT_MIN_NEW_CHARS]). A draft the recognizer rewrote
     * without growing it counts as new all the same: the translator would then have to work with different text.
     * Called on the asr thread.
     */
    private fun worthSendingDraft(segmentId: Long, ja: String): Boolean {
        // measured from the draft the translator last took (see mtLoop): neither a skipped one nor a queued one a
        // newer draft may still replace
        val lastText = sentDraft[segmentId]
        return lastText == null || newChars(lastText, ja) >= DRAFT_MIN_NEW_CHARS
    }

    /**
     * Characters of [b] that are not in [a]. A draft extends the one before it (measured prefix overlap 0.88), so this
     * is what the translator would newly have to work with, not the length difference (a rewrite can shorten the text).
     */
    private fun newChars(a: String, b: String): Int {
        val seen = HashSet<Char>(a.length * 2)
        for (c in a) seen.add(c)
        return b.count { it !in seen }
    }

    private fun judgeLoop() {
        while (running && !failed) {
            val (job, ja, setup) = pendingJudge.getAndSet(null) ?: run { Thread.sleep(10); null } ?: continue
            val judge = setup.judge ?: continue
            val call = judge.unfinished(ja)
            if (!running) { logJev("unfinished", job.segmentId, job.revision, call, "stopped"); return }
            reportJev(call, judge, setup)
            val p = call.noul
            // limited (now or already paused): finished, so the segmenter cuts at the pause instead of waiting
            // an answer to a request that a 429 overtook is from before the limit: finished too
            val unfinished = if (call.limit != null || judge.paused() != null || judge.limitedAfter(call.gen)) false
                else p?.let { it >= UNFINISHED_THRESHOLD }
            if (unfinished != null) {
                hints[job.segmentId to job.revision] = unfinished to call.gen
                hints.keys.removeIf { it.first < job.segmentId - 8 }
                hintsUsed.removeIf { it.first < job.segmentId - 8 }
            }
            // "hold"/"finish" is only the advice; a "hint_used" event shows when the segmenter acted on it
            logJev("unfinished", job.segmentId, job.revision, call, when {
                unfinished == null -> "none"
                job.segmentId <= lastFinalSeg -> "late"
                unfinished -> "hold"
                else -> "finish"
            })
        }
    }

    private fun logJev(kind: String, seg: Long, rev: Int, c: JevCall, action: String) = log(mapOf(
        "ev" to "jev", "kind" to kind, "seg" to seg, "rev" to rev, "request" to c.request, "status" to c.status,
        "response" to c.response, "ms" to c.ms, "error" to c.error, "action" to action))

    /** A Jev answer as the overlay sees it: an answer is a success only while no pause is on (a late old request). */
    private fun reportJev(c: JevCall, judge: Judge, setup: Setup) {
        // a question skipped while paused asked nothing; the overlay reads the pause from the judge itself
        if (c.error == "cancelled" || c.error == "paused") return
        val paused = judge.paused()
        // a 429 still being installed has no end yet: kept as "limited", its end read from the judge when shown
        val limit = c.limit ?: paused?.takeIf { it !== JevLimit.INSTALLING }
        val limited = c.limit != null || paused != null || c.status == 429
        onService(true, ServiceCall(c.answer != null && !limited, c.status, c.error, limit, setup.version, limited))
    }

    /** Japanese of the last [MAX_PREVIOUS_SENTENCES] non-empty finals translated, oldest first. MT thread only. */
    private val recentFinals = ArrayDeque<String>()
    /** newest segment whose final went through [rememberFinal] (MT thread only) */
    private var rememberedSeg = 0L
    /**
     * The context of a segment, fixed once the final before it was translated, so its later drafts, its final and a
     * retry share one prompt (and the reuse cache keeps working). Until then (a draft of the next segment translated
     * before the previous final) it is computed fresh and not kept. MT thread only.
     */
    private val contexts = HashMap<Long, List<String>>()
    private fun previousFor(seg: Long, n: Int): List<String> {
        contexts[seg]?.let { return it }
        val now = recentFinals.toList().takeLast(n)
        if (rememberedSeg >= seg - 1) { contexts.keys.removeIf { it < seg - 8 }; contexts[seg] = now }
        return now
    }
    private fun rememberFinal(seg: Long, ja: String) {
        if (seg > rememberedSeg) rememberedSeg = seg
        if (ja.none { it.isLetterOrDigit() }) return
        recentFinals.addLast(ja)
        while (recentFinals.size > MAX_PREVIOUS_SENTENCES) recentFinals.removeFirst() // a later setup may ask for more
    }

    /** One MT call, logged with everything needed to replay it. */
    private fun translate(job: AsrJob, setup: Setup, attempt: Int, ja: String, notes: List<String>,
                          previous: List<String>): MtResult? {
        val prompt = byokUser(ja, notes, previous)
        // Greedy decoding with state cleared per call: the same prompt gives the same output. A final usually
        // repeats its last draft's text, so that segment's last good translation is reused instead of computed again.
        // The pipeline (and so this cache) lives for one session, with one translator.
        val hit = lastMt?.takeIf { it.seg == job.segmentId && it.prompt == prompt && it.setup == setup.version }
        val t0 = clock()
        val r = hit?.result ?: setup.mt.translate(prompt)
        log(mapOf("ev" to "mt", "seg" to job.segmentId, "rev" to job.revision, "final" to job.isFinal, "attempt" to attempt,
            "prompt" to prompt, "request" to r?.request, "raw" to r?.raw, "stop" to r?.stop?.toString(),
            "http" to r?.http, "response" to r?.response, "error" to r?.error, "prompt_tokens" to r?.promptTokens,
            "output_tokens" to r?.outputTokens,
            "first_content_ms" to r?.firstContentMs, "cached_tokens" to r?.cachedTokens, "thinking" to r?.thinking, "ms" to clock() - t0,
            // reused: everything but ms is that of the earlier call (rev reused_from)
            "reused" to (hit != null), "reused_from" to hit?.rev, "setup" to setup.version))
        if (hit == null && r != null && r.usable) lastMt = LastMt(job.segmentId, job.revision, prompt, r, setup.version)
        // a reused result asked no service; a stop's cancelled call says nothing about it
        if (hit == null && r != null && running && r.error != "cancelled")
            onService(false, ServiceCall(r.usable, r.http ?: 0, r.error, null, setup.version))
        return r
    }

    private class LastMt(val seg: Long, val rev: Int, val prompt: String, val result: MtResult, val setup: Int)
    /** MT thread only. */
    private var lastMt: LastMt? = null

    /**
     * Final only: ask Jev what is wrong with [zh]; for a class it names reliably, translate once more with the
     * matching note. Returns the text to show (the retry if it produced one, else [zh]).
     */
    private fun checked(j: MtJob, zh: String, previous: List<String>, judgeMs: (Long) -> Unit, retryMs: (Long) -> Unit): String {
        // the segment's setup: no judge there (key missing for now) = this final is not checked
        val judge = j.setup.judge?.takeIf { checkTranslations } ?: return zh
        if (zh.isEmpty()) return zh
        val job = j.q.job
        val c0 = clock()
        val call = judge.mistranslation(j.ja, zh) // limited: returns at once without asking, the translation is kept
        judgeMs(clock() - c0) // the Jev call only; a retranslation below is translation time
        if (!running) { logJev("mistranslation", job.segmentId, job.revision, call, "stopped"); return zh }
        reportJev(call, judge, j.setup)
        // limited meanwhile (this answer may be an older request's): the translation is kept, nothing is retried
        val note = call.choice?.takeIf { call.limit == null && judge.paused() == null && !judge.limitedAfter(call.gen) }
            ?.let { fixNote(it, j.setup.source, j.setup.target) }?.takeIf { (call.confidence ?: 0.0) >= FIX_MIN_CONFIDENCE }
        if (note == null) {
            logJev("mistranslation", job.segmentId, job.revision, call, if (call.answer == null) "none" else "keep")
            return zh
        }
        logJev("mistranslation", job.segmentId, job.revision, call, "retranslate:${call.choice}")
        // only the MT thread translates (one translator, one cache): hand the retry over and wait for it
        val req = Retry(j, note, previous) { judge.paused() != null || judge.limitedAfter(call.gen) }
        synchronized(lock) { retryQueue.addLast(req) }
        while (running && !failed && !req.done.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
        // an internal failure of the MT thread fails the pipeline (guarded); never publish on top of it
        if (req.crashed) throw IllegalStateException("retranslation failed on the MT thread")
        retryMs(req.ms)
        // a 429 since the answer (also while the retry ran): that answer is stale, the first translation stays
        if (req.stale()) return zh
        return req.result?.takeIf { it.usable }?.text ?: zh
    }

    /**
     * A retranslation the check thread asks the MT thread for; [done] is counted down whatever happened. [stale]: the
     * Jev answer that asked for it was overtaken by a 429 (it is then not run, and its result not used).
     */
    private class Retry(val j: MtJob, val note: String, val previous: List<String>, val stale: () -> Boolean) {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var result: MtResult? = null
        @Volatile var ms = 0L
        /** the MT thread threw while translating it (not a failed or unusable translation, which keeps the first one) */
        @Volatile var crashed = false
    }
    private val retryQueue = LinkedBlockingDeque<Retry>()

    /** A final on its way through the check thread: [zh] is the first translation (or the failure text). */
    private class ToCheck(val j: MtJob, val zh: String, val needsCheck: Boolean, val start: Long, val mtMs: Long,
                          /** the context the first translation used: a retry uses the same, however late it runs */
                          val previous: List<String>)
    private val checkQueue = LinkedBlockingDeque<ToCheck>()
    /** finals queued for or inside the check (guarded by [lock]); they are also in [inFlightFinals] */
    private var inCheck = 0

    /**
     * With [checkTranslations], every final goes through here in order (also one that is not checked, so a later
     * segment never overtakes an earlier one on the board). The MT thread meanwhile goes on with the next segments.
     */
    private fun checkLoop() {
        while (running && !failed) {
            val c = synchronized(lock) { checkQueue.pollFirst() } ?: run { Thread.sleep(10); null } ?: continue
            val job = c.j.q.job
            var checkMs = 0L; var retryMs = 0L
            val zh = if (c.needsCheck) checked(c.j, c.zh, c.previous, { checkMs = it }, { retryMs = it }) else c.zh
            if (!running || failed) return
            val end = clock()
            // the interim line took the final's revision; the final goes out as revision + 1
            onCaption(caption(c.j, true, job.revision + 1, zh, c.start, end, c.mtMs + retryMs, checkMs))
            val backlog = synchronized(lock) {
                pendingFinalAudioMs -= audioMs(job); inCheck--; inFlightFinals--
                pendingFinals() + inFlightFinals
            }
            onBacklog(backlog)
        }
    }

    private fun mtLoop() {
        while (running && !failed) {
            // a retranslation the check thread waits on goes first; it is part of a final already counted
            val req = synchronized(lock) { retryQueue.pollFirst() }
            if (req != null) {
                try {
                    val t0 = clock()
                    if (!req.stale()) req.result = translate(req.j.q.job, req.j.setup, 2, req.j.ja, listOf(req.note), req.previous)
                    req.ms = clock() - t0
                } catch (t: Throwable) {
                    // visible to the waiting check thread before it wakes: it must not publish this final as checked
                    req.crashed = true; throw t
                } finally { req.done.countDown() }
                continue
            }
            val j = take(mtQueue) { it.q.job.isFinal } ?: continue
            if (!j.q.job.isFinal && skipDisplayDraft()) continue
            if (!j.q.job.isFinal) synchronized(lock) { sentDraft[j.q.job.segmentId] = j.ja }
            val job = j.q.job
            val start = clock()
            val ctx = previousFor(job.segmentId, j.setup.previousSentences)
            // nothing to translate in a segment of only punctuation ("。" "…"): a translator answers it with commentary
            val translatable = j.ja.any { it.isLetterOrDigit() }
            val r = if (translatable) translate(job, j.setup, 1, j.ja, emptyList(), ctx) else null
            if (job.isFinal) rememberFinal(job.segmentId, j.ja) // context for the following segments, also when this one fails
            if (!running) return
            var zh = if (translatable) r?.takeIf { it.usable }?.text else ""
            if (zh == null) {
                if (!job.isFinal) continue
                zh = "[翻译失败] ${j.ja}"
            }
            val end = clock()
            if (job.isFinal && checkTranslations) {
                val real = r?.takeIf { it.usable } != null && zh.isNotEmpty()
                // live mode shows the first translation while Jev checks it; "only finished sentences" hides it
                if (real) onCaption(caption(j, false, job.revision, zh, start, end, end - start))
                synchronized(lock) { inCheck++; checkQueue.addLast(ToCheck(j, zh, real, start, end - start, ctx)) }
                continue
            }
            val backlog = synchronized(lock) {
                if (job.isFinal) pendingFinalAudioMs -= audioMs(job)
                pendingFinals() + inFlightFinals
            }
            onCaption(caption(j, job.isFinal, job.revision, zh, start, end, end - start))
            if (job.isFinal) synchronized(lock) { inFlightFinals-- }
            onBacklog(backlog - if (job.isFinal) 1 else 0)
        }
    }

    private fun caption(j: MtJob, final: Boolean, revision: Int, zh: String, start: Long, end: Long, mtMs: Long, checkMs: Long = 0): Caption {
        val job = j.q.job
        return Caption(
            job.segmentId, revision, final, j.ja, zh.trim(),
            Timing(
                audioMs = audioMs(job),
                segmentWaitMs = j.q.submittedAt - job.audioEndAt,
                asrQueueMs = j.asrStartAt - j.q.submittedAt,
                asrMs = j.asrEndAt - j.asrStartAt,
                mtQueueMs = start - j.asrEndAt,
                mtMs = mtMs,
                mtDoneMs = end - job.audioEndAt,
                checkMs = checkMs,
            ),
            job.audioEndAt,
            job.timeEstimated,
            sourceRevision = job.revision,
        )
    }

    private fun audioMs(job: AsrJob) = job.samples.size * 1000L / sampleRate

    /**
     * A draft only updates the live line. Skip it when drafts are off, or when a final is waiting for ASR or MT:
     * on a slow phone drafts otherwise take the time finals need and the backlog keeps growing.
     */
    // finals that only wait for their Jev check do not hold drafts back (the MT thread is free for them)
    private fun skipDisplayDraft() = !drafts() || synchronized(lock) { pendingFinals() + inFlightFinals - inCheck > 0 }

    companion object {
        /** Jev "unfinished" probability from which a pause is held (140-pair test: catches 30/31, waits wrongly 16/109). */
        const val UNFINISHED_THRESHOLD = 0.65
        /** Below this Jev confidence an error class is not acted on (untested guess). */
        const val FIX_MIN_CONFIDENCE = 0.6
        /** a draft whose words may be brought back (see recognizeKeepingLostDraft): at least 5 characters */
        const val LOST_MIN_CHARS = 5
        /**
         * A draft is only translated when the recognition grew by this many characters. Measured on a 15 min VTuber
         * clip: one sentence is recognized 4.5 times on average and its text is only ~2 characters longer than the one
         * before (54% of drafts grew by <=2, 71% by <=5), so most calls repeat work that is already on screen. One call
         * costs ~900 ms, more than the [Segmenter]'s 700 ms between drafts, so the translator ran at 76% occupancy and
         * queued up. This threshold alone drops 29% of the calls; no time-based gate is added, because the single MT
         * thread and its queue already drop superseded drafts when it is busy, while a time gate would only make an
         * idle translator wait. Finals are never skipped.
         */
        const val DRAFT_MIN_NEW_CHARS = 5
        /** finished sentences given to the translator as context (the prompt's 【前文】): default, and the most offered */
        const val PREVIOUS_SENTENCES = 2
        const val MAX_PREVIOUS_SENTENCES = 5
        /** a stretch of the new result without words this long (s) where the draft had words is a loss */
        private const val LOSS_GAP_S = 0.6f
        /** fewer of the draft's characters in such a stretch are not brought back */
        private const val LOSS_MIN_CHARS = 4
        /** the draft's words this close (s) to the new result's words around the stretch belong to them */
        private const val LOSS_EDGE_S = 0.1f
        /** the leading stretch ends this far (s) before the new result's second piece (its first one's time is a guess) */
        private const val LOSS_LEAD_EDGE_S = 0.25f

        fun chars(ps: List<Piece>) = ps.sumOf { it.text.trim().length }
        fun textOf(ps: List<Piece>) = ps.joinToString("") { it.text }.trim()
        /** at least half of [words]' characters are in [text], in order (spaces aside) */
        private fun covered(words: String, text: String): Boolean {
            val w = words.filterNot { it.isWhitespace() }
            return 2 * lcsLength(w, text.filterNot { it.isWhitespace() }) >= w.length
        }

        /**
         * [whole] with the words of [draft] (the newest draft, times on the same audio) put back where [whole] has
         * none for at least [LOSS_GAP_S], [draft] has at least [LOSS_MIN_CHARS] characters and less than half of them
         * are in [whole] around there; null when nothing is missing. Before the second piece of [whole] (its first
         * one's time is a guess) the draft's words go first as one block, and [whole]'s first piece is left out when it
         * repeats the block's first or last characters (anywhere in it when it is the only piece); with [dedupe] (the
         * user's "drop repeats at a join"), the block's end is left out when [whole] starts with the same Japanese
         * words recognized again ("火の石って" before "火の意志って"). After the last piece nothing is added: the drop is of
         * what came before a pause, and a draft's last words are often reworded. Japanese and English alike: a space is
         * put where two runs of Latin words meet.
         */
        fun repairLoss(whole: List<Piece>, draft: List<Piece>, audioSec: Float, dedupe: Boolean): List<Piece>? {
            if (whole.isEmpty()) return null
            val out = ArrayList<Piece>()
            var changed = false
            // with one piece only, the whole audio (to the end of the padding) is the leading stretch
            val t1 = if (whole.size >= 2) whole[1].t else audioSec + PAD_S
            val lead = draft.filter { it.t < t1 - LOSS_LEAD_EDGE_S }.toMutableList()
            var rest = whole
            val head = textOf(lead)
            // a stretch is only lost when its words are not in [whole] after all (the first piece's time is a guess,
            // and an unchanged long first word spans the stretch): half of them in [whole]'s words up to the gap's
            // length after the block's last one is no loss
            if (chars(lead) >= LOSS_MIN_CHARS && t1 + PAD_S >= LOSS_GAP_S &&
                !covered(head, textOf(whole.filter { it.t < lead.last().t + LOSS_GAP_S }))) {
                val first = whole[0].text.trim()
                val repeated = if (whole.size == 1) first in head else first in head.take(2) || first in head.takeLast(2)
                if (first.isNotEmpty() && repeated) rest = whole.drop(1)
                // Latin words are not matched letter by letter ("THERE" is no repeat of "HERE")
                if (dedupe && !latin(head) && !latin(textOf(rest))) {
                    val rt = textOf(rest)
                    for (k in minOf(head.length, rt.length, 10) downTo 1) {
                        val end = head.takeLast(k)
                        if (end[0] != rt[0] || (k - 1..k + 1).none { j -> j in 1..rt.length &&
                                lcsLength(end, rt.take(j)) >= maxOf(minOf(k, 2), Math.round(0.7 * maxOf(k, j)).toInt()) }) continue
                        var dropped = 0
                        while (lead.isNotEmpty() && dropped < k) dropped += lead.removeAt(lead.size - 1).text.trim().length
                        break
                    }
                }
                if (lead.isEmpty()) rest = whole
                else { glue(out, lead); changed = true }
            }
            // only the pieces of [whole] after its first one have known times
            val skipped = whole.size - rest.size
            var meet = changed
            for ((i, p) in rest.withIndex()) {
                if (meet) glue(out, listOf(p)) else out += p
                meet = false
                val next = rest.getOrNull(i + 1) ?: break
                if (i + skipped < 1 || next.t - p.t < LOSS_GAP_S) continue
                val miss = draft.filter { it.t > p.t + LOSS_EDGE_S && it.t < next.t - LOSS_EDGE_S }
                if (chars(miss) < LOSS_MIN_CHARS || covered(textOf(miss), p.text + next.text)) continue
                glue(out, miss)
                changed = true
                meet = true
            }
            return if (changed) out else null
        }

        /** [add] after [to], with a space at the meeting point where two runs of Latin words meet */
        private fun glue(to: MutableList<Piece>, add: List<Piece>) {
            if (add.isEmpty()) return
            val a = to.lastOrNull()?.text ?: ""
            val b = add[0].text
            val spaced = a.isNotEmpty() && join(a, b).length > a.length + b.length
            to += if (spaced) Piece(" $b", add[0].t) else add[0]
            to += add.drop(1)
        }
        private const val PAD_S = Recognition.PAD_S

        /** a letter of the Latin (incl. full-width), Cyrillic or Greek script: text whose words are separated by spaces */
        private fun spacedLetter(c: Char) = c.isLetter() && Character.UnicodeScript.of(c.code).let {
            it == Character.UnicodeScript.LATIN || it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK
        }
        private fun latin(s: String) = s.any(::spacedLetter)
        /**
         * Latin-script text is joined with a space when neither side of the join is CJK (or already whitespace), so
         * "THANK YOU." + "SO" gets one; Japanese, also with a Latin word in it ("SC" + "です"), is joined directly.
         */
        fun join(a: String, b: String) = when {
            a.isEmpty() -> b; b.isEmpty() -> a
            (latin(a) || latin(b)) && !a.last().isWhitespace() && !b.first().isWhitespace() &&
                !cjk(a.last()) && !cjk(b.first()) -> "$a $b"
            else -> a + b
        }
        /** a character of text written without spaces: anything at or above the CJK ranges except spaced letters (full-width Latin) */
        private fun cjk(c: Char) = c.code >= 0x2E80 && !spacedLetter(c)

        /** longest common subsequence of [a] and [b]; strings are a sentence long */
        fun lcsLength(a: String, b: String): Int {
            val dp = IntArray(b.length + 1)
            for (x in a) {
                var prev = 0
                for (j in 1..b.length) { val cur = dp[j]; dp[j] = if (x == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1]); prev = cur }
            }
            return dp[b.length]
        }
    }

    // caller holds lock
    private fun pendingFinals() = asrQueue.count { it.job.isFinal } + mtQueue.count { it.q.job.isFinal }
}

/**
 * What the overlay shows: finished sentences, which never change once shown, and below them one separate draft
 * line for the sentence still being spoken. A draft never replaces or pushes out a final.
 * The oldest final leaves only while there are more than [minFinals] finals AND their Chinese text is longer than
 * [maxChars] code points (drafts and Japanese are not counted).
 * Guards: newer revisions only, nothing after a segment's final, no late drafts of older segments.
 */
class CaptionBoard(private var minFinals: Int = DEFAULT_SENTENCES, private var maxChars: Int = DEFAULT_CHARS) {
    private val finals = ArrayDeque<Caption>()
    private var draft: Caption? = null
    /** newest final ever accepted: a removed sentence must not come back */
    private var lastFinal = Long.MIN_VALUE

    /** @return true if the board changed */
    @Synchronized
    fun accept(c: Caption): Boolean {
        // a segment at or before the newest final is done: no second final, no late draft, also after it was trimmed
        if (c.segmentId <= lastFinal) return false
        val d = draft
        if (c.isFinal) {
            if (d != null && d.segmentId <= c.segmentId) draft = null
            lastFinal = c.segmentId
            if (c.zh.isEmpty()) return d != null && draft == null
            finals.addLast(c)
            trim()
            return true
        }
        if (d != null && (c.segmentId < d.segmentId || (c.segmentId == d.segmentId && c.revision <= d.revision))) return false
        draft = c
        return true
    }

    /** Settings changed while running. @return true if sentences were removed */
    @Synchronized
    fun setLimits(sentences: Int, chars: Int): Boolean {
        minFinals = sentences.coerceAtLeast(1); maxChars = chars.coerceAtLeast(0)
        return trim()
    }

    private fun trim(): Boolean {
        var removed = false
        while (finals.size > minFinals && finals.sumOf { it.zh.codePointCount(0, it.zh.length) } > maxChars) {
            finals.removeFirst(); removed = true
        }
        return removed
    }

    /** finals oldest first, then the draft (if any) */
    @Synchronized
    fun snapshot(): List<Caption> = finals.toList() + listOfNotNull(draft)

    companion object {
        const val DEFAULT_SENTENCES = 2
        const val DEFAULT_CHARS = 100
    }
}
