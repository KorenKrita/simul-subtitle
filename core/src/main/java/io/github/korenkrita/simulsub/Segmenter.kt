package io.github.korenkrita.simulsub

/**
 * Turns a 16 kHz mono stream into ASR jobs.
 *
 * Feed it fixed [frameSize] frames together with a speech probability for each frame
 * (Silero VAD on device, anything in tests). It emits:
 * - a draft with the whole utterance so far every [draftEveryMs] while speech continues;
 * - a final when silence lasts [endSilenceMs], or when the utterance reaches [maxUtteranceMs].
 *   A forced cut goes into the longest pause of the utterance (see [forcedCut]); the audio after the
 *   cut starts the next utterance, so no sample is lost or duplicated.
 */
class Segmenter(
    private val emit: (AsrJob) -> Unit,
    private val sampleRate: Int = 16_000,
    val frameSize: Int = 512,
    private val threshold: Float = 0.5f,
    private val endSilenceMs: Int = 400,
    private val draftEveryMs: Int = 700,
    private val minDraftMs: Int = 600,
    private val maxUtteranceMs: Int = 7_000,
    private val preRollMs: Int = 200,
    /**
     * A: an utterance with less speech than this is not finalized at [endSilenceMs]; it waits up to
     * [maxHoldSilenceMs] of silence for the speaker to continue, and continuation joins the same segment.
     * Short fragments ("えっと", a clause cut by a breath) are what ASR and MT get wrong alone. 0 = off.
     */
    private val minFinalMs: Int = 0,
    private val maxHoldSilenceMs: Int = 1_500,
    /**
     * > 0 enables the judged hold. A candidate end is a pause of [endSilenceMs] or a forced cut at [maxUtteranceMs];
     * for each one a draft with [AsrJob.endOfSpeech] holding exactly the audio up to that end is emitted, and
     * [holdHint] is asked about that revision: true = unfinished (keep listening), false = finished (final now),
     * null = no answer yet (wait at most this long after the candidate was emitted, then final).
     * Bounds: a pause still ends at [maxHoldSilenceMs]; a forced cut that was judged unfinished is cut for good at
     * twice [maxUtteranceMs]. Answers for an older revision are never used.
     */
    private val hintWaitMs: Int = 0,
    /** Read on the audio thread; must not block. */
    private val holdHint: (segmentId: Long, revision: Int) -> Boolean? = { _, _ -> null },
    /**
     * Called once when an utterance starts (audio thread, must not block), also without a judged hold: the pipeline
     * fixes the segment's settings then, so a change while it is spoken applies from the next one. With [hintWaitMs]:
     * false = this one is cut as without a judge (Jev paused, its key is missing): no candidate questions and
     * [plainMaxUtteranceMs].
     */
    private val judged: ((segmentId: Long) -> Boolean)? = null,
    /** [maxUtteranceMs] for an utterance [judged] said no to */
    private val plainMaxUtteranceMs: Int = maxUtteranceMs,
    /**
     * With [hintWaitMs]: the pause candidate is emitted (recognized and asked about) after this much silence, still
     * cut only at [endSilenceMs]; the answer then often is there when the pause is. Speech before [endSilenceMs] drops
     * it as no pause. Default [endSilenceMs]: asked at the pause.
     */
    private val askSilenceMs: Int = endSilenceMs,
    /**
     * Without a judged hold: an utterance with this much audio before its pause ends at a pause of [earlyCutSilenceMs]
     * already, shorter than [endSilenceMs]. A speaker who reads comments and chats on often pauses less than
     * [endSilenceMs] for many seconds; otherwise only [maxUtteranceMs] cuts them, and the forced cut goes back to the
     * pause it finds, so that sentence's final comes seconds after it was spoken. 0 = off. (A judge asks at its own
     * [askSilenceMs] instead.)
     */
    private val earlyCutAfterMs: Int = 0,
    private val earlyCutSilenceMs: Int = endSilenceMs,
) {
    private val frameMs = frameSize * 1000 / sampleRate
    private val preRollFrames = preRollMs / frameMs

    private class Frame(val pcm: FloatArray, val speech: Boolean, val endAt: Long, val estimated: Boolean)

    private val preRoll = ArrayDeque<Frame>()
    private val frames = ArrayList<Frame>()
    private var inSpeech = false
    private var silentFrames = 0
    private var framesSinceDraft = 0
    private var segmentId = 0L
    private var revision = 0
    /** end-of-speech candidate for the current pause: its revision (-1 = none), cut and frames.size when it was emitted */
    private var tailRevision = -1
    private var tailCut = 0
    private var tailAtFrames = 0
    /** the candidate was emitted at [askSilenceMs], before the pause reached [endSilenceMs] */
    private var tailEarly = false
    /**
     * A pause candidate the speaker talked past before its answer came: its answer is still waited for, within
     * 2 x [hintWaitMs] of the candidate. "Finished" = the sentence before the pause is done, cut there and carry the
     * speech after it into the next segment; "unfinished", a newer candidate or the time limit drop it.
     */
    private var resumedRevision = -1
    private var resumedCut = 0
    private var resumedAtFrames = 0
    /** forced-cut candidate: revision (-1 = none), where it cuts, and frames.size when it was emitted */
    private var cutRevision = -1
    private var cutIndex = 0
    private var cutAtFrames = 0
    /** this utterance's forced cut was judged unfinished or superseded: no further cut question until the hard limit */
    private var cutClosed = false
    /**
     * The candidate whose "unfinished" answer keeps this utterance growing now (revision, -1: none, and where it cuts):
     * the forced cut's, or a pause candidate's accepted after speech resumed. Rechecked per frame: an answer made stale
     * later (a 429) cuts there.
     */
    private var heldRevision = -1
    private var heldCut = 0
    // this utterance's judged-hold settings, fixed when it starts ([beginUtterance])
    private var waitMs = hintWaitMs
    private var maxMs = maxUtteranceMs
    private var hardMaxMs = if (hintWaitMs > 0) 2 * maxUtteranceMs else maxUtteranceMs

    private fun beginUtterance() {
        val answer = judged?.invoke(segmentId) // always asked: it also fixes the segment's settings
        val on = hintWaitMs > 0 && answer != false
        waitMs = if (on) hintWaitMs else 0
        // a paused judge cuts at the plain limit; without a judged hold maxUtteranceMs already is the plain one
        maxMs = if (on || hintWaitMs <= 0) maxUtteranceMs else plainMaxUtteranceMs
        hardMaxMs = if (waitMs > 0) 2 * maxMs else maxMs
    }

    /**
     * @param now [monoMs] capture time of this frame's last sample
     * @param estimated [now] is a read time rather than a HAL capture timestamp
     */
    fun accept(pcm: FloatArray, speechProb: Float, now: Long, estimated: Boolean = false) {
        val f = Frame(pcm, speechProb >= threshold, now, estimated)
        if (!inSpeech) {
            if (!f.speech) {
                preRoll.addLast(f)
                if (preRoll.size > preRollFrames) preRoll.removeFirst()
                return
            }
            inSpeech = true
            segmentId++; beginUtterance()
            revision = 0
            frames.addAll(preRoll)
            preRoll.clear()
            silentFrames = 0
            framesSinceDraft = 0
        }

        frames.add(f)
        framesSinceDraft++
        silentFrames = if (f.speech) 0 else silentFrames + 1
        if (f.speech && tailRevision >= 0) {
            // a candidate asked about before the pause was long enough is just dropped: there was no pause
            if (tailEarly) tailEarly = false
            else { resumedRevision = tailRevision; resumedCut = tailCut; resumedAtFrames = tailAtFrames }
            tailRevision = -1
        }
        if (resumedRevision >= 0) {
            val answer = if ((frames.size - resumedAtFrames) * frameMs > 2 * waitMs) true else holdHint(segmentId, resumedRevision)
            if (answer == false) { finalize(resumedCut, continueSpeech = true); return }
            if (answer == true) {
                // a real "unfinished" (not the time limit) holds from here on, in place of any earlier hold
                if ((frames.size - resumedAtFrames) * frameMs <= 2 * waitMs) { heldRevision = resumedRevision; heldCut = resumedCut }
                resumedRevision = -1
            }
        }
        // a hold taken earlier is only as good as its answer: one a 429 made stale since cuts where it would have
        if (heldRevision >= 0 && resumedRevision < 0 && tailRevision < 0 && holdHint(segmentId, heldRevision) == false) {
            finalize(heldCut, continueSpeech = true); return
        }

        val durMs = frames.size * frameMs
        val silMs = silentFrames * frameMs
        // keep ~100 ms of the trailing silence
        val speechEnd = (frames.size - silentFrames + 100 / frameMs).coerceIn(1, frames.size)
        val inPause = silMs >= endSilenceMs
        when {
            // a held pause is bounded by maxHoldSilenceMs; no forced cut inside it (the final drops the silence)
            inPause && (durMs >= hardMaxMs || !holding(silMs)) -> finalize(speechEnd, continueSpeech = false)
            inPause -> if (tailEarly) {
                // the candidate asked early is now a real pause: from here on it is one as if emitted now
                tailEarly = false
                if (cutRevision >= 0) { cutRevision = -1; cutClosed = true }
                heldRevision = -1
                resumedRevision = -1
                tailAtFrames = frames.size
            } else if (waitMs > 0 && tailRevision < 0 && speechMs() >= minFinalMs) {
                // a newer candidate end replaces an open forced-cut question, and closes it for this utterance
                if (cutRevision >= 0) { cutRevision = -1; cutClosed = true }
                heldRevision = -1 // and a hold taken before: this newer candidate decides now
                resumedRevision = -1 // this newer candidate supersedes one the speaker talked past
                tailRevision = ++revision
                tailCut = speechEnd
                tailAtFrames = frames.size
                emit(AsrJob(segmentId, tailRevision, false, concat(speechEnd), frames[speechEnd - 1].endAt, frames[speechEnd - 1].estimated, endOfSpeech = true))
            }
            // ask early (see askSilenceMs): the same audio as at the pause, so its answer is there sooner. Only at a
            // plain pause: no other question open (the pipeline asks one at a time, a newer one replaces it), no hold
            // to recheck, and no forced cut due before the pause can count (durMs grows by at most the ask window)
            silMs >= askSilenceMs && waitMs > 0 && tailRevision < 0 && speechMs() >= minFinalMs && cutRevision < 0 &&
                resumedRevision < 0 && heldRevision < 0 && durMs + endSilenceMs - askSilenceMs < maxMs -> {
                tailRevision = ++revision
                tailCut = speechEnd
                tailAtFrames = frames.size
                tailEarly = true
                emit(AsrJob(segmentId, tailRevision, false, concat(speechEnd), frames[speechEnd - 1].endAt, frames[speechEnd - 1].estimated, endOfSpeech = true))
            }
            // a long utterance at a short pause: end it there, as at the end silence (see earlyCutAfterMs)
            waitMs <= 0 && earlyCutAfterMs > 0 && silMs >= earlyCutSilenceMs && durMs - silMs >= earlyCutAfterMs ->
                finalize(speechEnd, continueSpeech = false)
            durMs >= maxMs && forcedCutStep(durMs) -> Unit
            framesSinceDraft * frameMs >= draftEveryMs && durMs >= minDraftMs && f.speech -> {
                framesSinceDraft = 0
                emit(AsrJob(segmentId, ++revision, false, concat(frames.size), now, estimated))
            }
        }
    }

    private fun speechMs() = frames.count { it.speech } * frameMs

    /** Wait for continuation instead of finalizing at the normal end silence. Bounded by [maxHoldSilenceMs]. */
    private fun holding(silMs: Int): Boolean {
        if (silMs >= maxHoldSilenceMs) return false
        if (speechMs() < minFinalMs) return true
        if (waitMs <= 0) return false
        if (tailRevision < 0) return true // the candidate is emitted on this frame
        // asked early: the wait for its answer starts when the pause counts (see accept), as if asked then
        return holdHint(segmentId, tailRevision) ?: (tailEarly || (frames.size - tailAtFrames) * frameMs < waitMs)
    }

    /**
     * Without a judge, cut now. With one, ask about the cut first; an unfinished head keeps growing up to [hardMaxMs].
     * One question per utterance: once answered "unfinished" (or replaced by a pause candidate) it is closed and a later
     * answer about it is ignored. @return true if this frame was consumed (cut or candidate emitted), false = go on
     * with the normal draft logic while the speaker keeps talking.
     */
    private fun forcedCutStep(durMs: Int): Boolean {
        if (waitMs <= 0 || durMs >= hardMaxMs) { finalize(forcedCut(), continueSpeech = true); return true }
        if (cutClosed) return false
        if (cutRevision < 0) {
            cutIndex = forcedCut()
            resumedRevision = -1 // a newer question about this utterance supersedes an older pause one
            heldRevision = -1 // and a pause hold taken before: this newer candidate decides now
            cutRevision = ++revision
            cutAtFrames = frames.size
            emit(AsrJob(segmentId, cutRevision, false, concat(cutIndex), frames[cutIndex - 1].endAt, frames[cutIndex - 1].estimated, endOfSpeech = true))
            return true
        }
        when (holdHint(segmentId, cutRevision)) {
            true -> { cutClosed = true; heldRevision = cutRevision; heldCut = cutIndex; cutRevision = -1 }
            false -> { finalize(cutIndex, continueSpeech = true); return true }
            null -> if ((frames.size - cutAtFrames) * frameMs >= waitMs) { finalize(cutIndex, continueSpeech = true); return true }
        }
        return false
    }

    /**
     * Where to split an utterance that hit [maxUtteranceMs].
     * Prefer the longest VAD-silent run (a real pause, often a sentence boundary) in the later 2/3 of the
     * utterance, cutting in its middle. ASR models drop a half-spoken word at the end of a chunk, so
     * cutting mid-word loses it; a pause loses nothing. Without any pause, cut at the end and let the
     * next chunk start fresh.
     */
    private fun forcedCut(): Int {
        var bestStart = -1; var bestLen = 0
        var i = frames.size / 3
        while (i < frames.size) {
            if (frames[i].speech) { i++; continue }
            val s = i
            while (i < frames.size && !frames[i].speech) i++
            if (i - s >= bestLen) { bestLen = i - s; bestStart = s } // ties → later pause
        }
        return if (bestLen >= 2) bestStart + bestLen / 2 + 1 else frames.size
    }

    /** Force-final whatever is pending (stop, pause); what comes after starts afresh, without the audio before. */
    fun flush() {
        if (inSpeech && frames.isNotEmpty()) finalize(frames.size, continueSpeech = false)
        preRoll.clear()
    }

    private fun finalize(count: Int, continueSpeech: Boolean) {
        emit(AsrJob(segmentId, ++revision, true, concat(count), frames[count - 1].endAt, frames[count - 1].estimated))
        tailRevision = -1
        tailEarly = false
        resumedRevision = -1
        cutRevision = -1
        cutClosed = false
        heldRevision = -1
        val tail = frames.subList(count, frames.size).toList()
        frames.clear()
        // A cut that leaves no speech behind ends the utterance: the next one starts with its first speech frame, and only
        // then fixes its settings (a change made in the silence after a forced cut applies to it). A tail of silence alone
        // would only be waited out and sent to recognition as a segment of nothing.
        if (continueSpeech && tail.any { it.speech }) {
            segmentId++; beginUtterance()
            revision = 0
            frames.addAll(tail)
            framesSinceDraft = 0
            // a forced cut lands in a pause, so the tail can end in silence: it counts towards the next pause
            silentFrames = tail.takeLastWhile { !it.speech }.size
        } else {
            inSpeech = false
            preRoll.clear()
        }
    }

    private fun concat(count: Int): FloatArray {
        val out = FloatArray(count * frameSize)
        for (i in 0 until count) System.arraycopy(frames[i].pcm, 0, out, i * frameSize, frameSize)
        return out
    }
}
