package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    private val frame = 512 // 32 ms @16k

    /** Feeds (speech?, ms) runs; each frame's samples carry its global index so cuts can be located exactly. */
    private fun run(pattern: List<Pair<Boolean, Int>>, seg: Segmenter): Int {
        var idx = 0
        for ((speech, ms) in pattern) repeat(ms / 32) {
            idx++
            seg.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, idx * 32L)
        }
        return idx
    }

    private fun frameIds(j: AsrJob) = (0 until j.samples.size / frame).map { j.samples[it * frame].toInt() }

    @Test
    fun draftsThenOneFinalPerUtterance() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add)
        run(listOf(false to 512, true to 2016, false to 608, true to 800, false to 608), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        assertTrue(jobs.count { !it.isFinal && it.segmentId == 1L } >= 2)
        val seg1 = jobs.filter { it.segmentId == 1L }
        assertEquals(seg1.map { it.revision }.sorted(), seg1.map { it.revision })
        assertTrue(seg1.last().isFinal)
        val sec = finals[0].samples.size / 16_000f
        assertTrue("final length $sec", sec in 2.0f..2.5f)
        // audioEndAt is the end of the last sample *in the job*, not the time the silence was confirmed
        assertEquals(frameIds(finals[0]).last() * 32L, finals[0].audioEndAt)
    }

    /**
     * The final's provenance is that of its last *kept* frame (end of speech + ~100 ms), not of the later
     * silent frame that triggered the cut. Both directions, so neither value can pass by accident.
     */
    @Test
    fun finalTakesProvenanceOfItsLastKeptFrameNotTheTriggeringOne() {
        for (keptEstimated in listOf(true, false)) {
            val jobs = ArrayList<AsrJob>()
            val s = Segmenter(jobs::add)
            var idx = 0
            fun feed(speech: Boolean, frames: Int, est: Boolean) = repeat(frames) { idx++; s.accept(FloatArray(frame), if (speech) 0.9f else 0.1f, idx * 32L, est) }
            feed(false, 10, false); feed(true, 50, false)
            val speechEnd = idx
            feed(false, 3, keptEstimated)    // the ~100 ms of trailing silence the final keeps
            feed(false, 20, !keptEstimated)  // later silence, including the frame that triggers the final
            val f = jobs.single { it.isFinal }
            assertEquals((speechEnd + 3) * 32L, f.audioEndAt)
            assertEquals("keptEstimated=$keptEstimated", keptEstimated, f.timeEstimated)
        }
    }

    @Test
    fun forcedCutLandsInThePauseAndLosesNoFrame() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 7_000, endSilenceMs = 400)
        // 4.5 s speech, 256 ms pause (shorter than endSilence → no natural final), 4 s speech
        val total = run(listOf(true to 4512, false to 256, true to 4000), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertEquals(2, finals.size)
        val ids = finals.flatMap(::frameIds)
        assertEquals("every frame exactly once", (1..total).toList(), ids)
        // first cut is inside the pause (frames 142..149)
        val cutAfter = frameIds(finals[0]).last()
        assertTrue("cut after frame $cutAfter", cutAfter in 142..149)
    }

    @Test
    fun theSilenceAfterAForcedCutCountsTowardsTheNextPause() {
        // 4.5 s speech, 320 ms pause, 2 s speech, silence: the 7 s cut lands in the pause, so the next segment starts
        // with silence; it must count, or the trailing silence is overcounted and the next final comes late
        val jobs = ArrayList<AsrJob>()
        val emittedAt = ArrayList<Int>()
        val s = Segmenter({ jobs.add(it); if (it.isFinal) emittedAt.add(fed) }, maxUtteranceMs = 7_000, endSilenceMs = 400)
        feed(listOf(true to 4512, false to 320, true to 1952, false to 1000), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(2, finals.size)
        assertEquals(147, frameIds(finals[0]).last())
        // speech ends at 212: the final keeps ~100 ms (3 frames) of it and is sent once 400 ms of silence are seen
        assertEquals((148..215).toList(), frameIds(finals[1]))
        assertEquals(225, emittedAt[1])
    }

    @Test
    fun aForcedCutLeavingOnlySilenceEndsTheUtterance() {
        // the 7 s cut comes in the silence after the speech: no second segment of pure silence goes to recognition
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 7_000, endSilenceMs = 400)
        feed(listOf(true to 6784, false to 3000), s)
        s.flush()
        assertEquals(1, jobs.count { it.isFinal })
    }

    @Test
    fun theSilenceLeftByAForcedCutLeadsTheNextUtterance() {
        // the 7 s cut lands in a short pause just before the speaker goes on: that silence is not dropped
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 7_000, endSilenceMs = 400)
        feed(listOf(true to 6784, false to 224, true to 1024, false to 1000), s)
        val ids = jobs.filter { it.isFinal }.flatMap(::frameIds)
        assertEquals(2, jobs.count { it.isFinal })
        assertEquals("no frame lost between the two", (1..ids.last()).toList(), ids)
    }

    @Test
    fun afterAFlushTheNextUtteranceHasNoAudioFromBefore() {
        // a pause flushes, drops audio, then goes on: the silence heard before the flush is not the next one's pre-roll
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add)
        feed(listOf(false to 640), s)
        val before = fed
        s.flush()
        feed(listOf(true to 1024, false to 800), s)
        assertTrue(frameIds(jobs.single { it.isFinal }).none { it <= before })
    }

    private var fed = 0
    private fun feed(pattern: List<Pair<Boolean, Int>>, seg: Segmenter) {
        for ((speech, ms) in pattern) repeat(ms / 32) {
            fed++
            seg.accept(FloatArray(frame) { fed.toFloat() }, if (speech) 0.9f else 0.1f, fed * 32L)
        }
    }

    @Test
    fun forcedCutWithoutPauseAlsoLosesNothing() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000)
        val total = run(listOf(true to 10_016), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.size >= 3)
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun silenceProducesNothing() {
        val jobs = ArrayList<AsrJob>()
        run(listOf(false to 5000), Segmenter(jobs::add))
        assertTrue(jobs.isEmpty())
    }

    // --- A: a short fragment is not finalized at the first short pause; it waits for continuation ---

    @Test
    fun shortFragmentFollowedQuicklyBySpeechBecomesOneFinal() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        // 640 ms speech, 600 ms pause (> endSilence 400, < hold 1500), 1500 ms speech, long silence
        val total = run(listOf(false to 320, true to 640, false to 608, true to 1504, false to 1600), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertEquals(setOf(1L), jobs.map { it.segmentId }.toSet())
        val ids = frameIds(finals[0])
        assertTrue("starts in pre-roll", ids.first() <= 11)
        assertTrue("covers both parts", ids.last() >= 10 + 20 + 19 + 47)
        assertTrue(ids.last() <= total)
        assertEquals("contiguous", (ids.first()..ids.last()).toList(), ids)
    }

    @Test
    fun shortFragmentFollowedByLongSilenceIsFinalizedAlone() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(false to 320, true to 640, false to 1600, true to 1504, false to 1600), s)
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        // the held final still keeps only ~100 ms of trailing silence, not the whole wait
        assertTrue(frameIds(finals[0]).last() <= 10 + 20 + 4)
    }

    @Test
    fun longEnoughUtteranceIsFinalizedAtNormalEndSilence() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(true to 1504, false to 608, true to 1504, false to 608), s)
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun holdHintKeepsALongUtteranceOpen() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = { seg, _ -> seg == 1L })
        run(listOf(true to 1504, false to 608, true to 1504, false to 1600), s)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun speechLengthIgnoresPreRollAndInternalSilence() {
        // 200 ms pre-roll + 704 ms speech + 288 ms pause + 320 ms speech: frames ≈ 1.5 s, speech ≈ 1.0 s < 1.2 s → still held
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(false to 320, true to 704, false to 288, true to 320, false to 608), s)
        assertTrue(jobs.none { it.isFinal })
        // just over the threshold → normal final at endSilence
        val jobs2 = ArrayList<AsrJob>()
        run(listOf(false to 320, true to 1248, false to 608), Segmenter(jobs2::add, minFinalMs = 1200, maxHoldSilenceMs = 1500))
        assertEquals(1, jobs2.count { it.isFinal })
    }

    /** The judged hold, with an answer that arrives at a given silence length (fake Jev latency). */
    private class FakeJudge(val answer: Boolean, val readyAfterFrames: Int) {
        val asked = ArrayList<Pair<Long, Int>>()
        var tail: AsrJob? = null
        var framesSinceTail = 0
        fun onJob(j: AsrJob) { if (j.endOfSpeech) { tail = j; framesSinceTail = 0 } }
        fun hint(seg: Long, rev: Int): Boolean? {
            asked.add(seg to rev)
            val t = tail ?: return null
            if (t.segmentId != seg || t.revision != rev) return null
            return if (++framesSinceTail >= readyAfterFrames) answer else null
        }
    }

    private fun judged(answer: Boolean, readyAfterFrames: Int, pattern: List<Pair<Boolean, Int>>): Pair<List<AsrJob>, FakeJudge> {
        val jobs = ArrayList<AsrJob>()
        val j = FakeJudge(answer, readyAfterFrames)
        val s = Segmenter({ jobs.add(it); j.onJob(it) }, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = j::hint)
        run(pattern, s); s.flush()
        return jobs to j
    }

    // middle pause 1408 ms: past the wait (candidate at ~416 ms + 900 ms), short of maxHold (1500) → the answer decides
    private val midPause = listOf(true to 1504, false to 1408, true to 1504, false to 1600)
    private val shortPause = listOf(true to 1504, false to 608, true to 1504, false to 1600)

    @Test
    fun judgeSaysFinishedInTimeFinalizesBeforeTheWaitEnds() {
        val (jobs, _) = judged(answer = false, readyAfterFrames = 3, pattern = shortPause)
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
        // the end-of-speech draft carries exactly the audio of the final that follows it
        val tail = jobs.first { it.endOfSpeech && it.segmentId == 1L }
        assertEquals(frameIds(jobs.first { it.isFinal }), frameIds(tail))
    }

    @Test
    fun judgeSaysUnfinishedInTimeJoinsTheContinuation() {
        val (jobs, _) = judged(answer = true, readyAfterFrames = 3, pattern = shortPause)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun lateAnswerFallsBackToFinalAtTheWaitLimit() {
        // the answer would come after ~1.3 s of silence; the segmenter gives up at 900 ms and finalizes
        val (jobs, _) = judged(answer = true, readyAfterFrames = 40, pattern = midPause)
        val f = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), f.map { it.segmentId })
        val firstFinalAt = jobs.indexOf(f[0])
        val tailAt = jobs.indexOfFirst { it.endOfSpeech && it.segmentId == 1L }
        assertTrue(tailAt in 0 until firstFinalAt)
    }

    @Test
    fun answerForAnOlderRevisionIsNotUsed() {
        // speech resumes during the wait, so a new pause asks about a new revision; the judge only knows the old one
        val jobs = ArrayList<AsrJob>()
        var firstTail: AsrJob? = null
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech && firstTail == null) firstTail = it }, minFinalMs = 1200,
            maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { seg, rev -> if (firstTail?.let { it.segmentId == seg && it.revision == rev } == true) true else null })
        run(listOf(true to 1504, false to 608, true to 1504, false to 1408, true to 1504, false to 1600), s)
        val tails = jobs.filter { it.endOfSpeech && it.segmentId == 1L }
        assertEquals(2, tails.size)
        assertTrue(tails[1].revision > tails[0].revision)
        // second pause: no answer for its revision → final at hintWaitMs (two segments), not held by the stale "true"
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun answerUnfinishedHoldsAcrossAPauseLongerThanTheWait() {
        val (jobs, _) = judged(answer = true, readyAfterFrames = 3, pattern = midPause)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    /** A judge that answers [latencyMs] after the end-of-speech draft was emitted (ASR + network), in audio time. */
    private fun timedJudge(answer: Boolean, latencyMs: Int, pattern: List<Pair<Boolean, Int>>, hintWaitMs: Int = 1000,
                           maxHoldSilenceMs: Int = 2500, maxUtteranceMs: Int = 7000, askSilenceMs: Int = 400,
                           times: MutableList<Long>? = null): List<AsrJob> {
        val jobs = ArrayList<AsrJob>()
        var now = 0L
        val sentAt = HashMap<Pair<Long, Int>, Long>()
        val s = Segmenter({ jobs.add(it); times?.add(now); if (it.endOfSpeech) sentAt[it.segmentId to it.revision] = now },
            minFinalMs = 1200, maxHoldSilenceMs = maxHoldSilenceMs, hintWaitMs = hintWaitMs, maxUtteranceMs = maxUtteranceMs,
            askSilenceMs = askSilenceMs,
            holdHint = { seg, rev -> sentAt[seg to rev]?.let { if (now - it >= latencyMs) answer else null } })
        var idx = 0
        for ((speech, ms) in pattern) repeat(ms / 32) {
            idx++; now = idx * 32L
            s.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, now)
        }
        s.flush()
        return jobs
    }

    @Test
    fun realisticJudgeLatencyStillJoinsTheContinuation() {
        // ASR ~100 ms + Jev ~550 ms after the candidate; the speaker resumes ~800 ms after the candidate (1216 ms pause)
        val jobs = timedJudge(answer = true, latencyMs = 650, pattern = listOf(true to 1504, false to 1216, true to 1504, false to 3000))
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun realisticJudgeLatencyFinishedAnswerFinalizesWithoutWaitingForMaxHold() {
        val jobs = timedJudge(answer = false, latencyMs = 650, pattern = listOf(true to 1504, false to 3000))
        val f = jobs.single { it.isFinal }
        // final at ~416 ms (candidate) + 650 ms answer, well before the 2.5 s hold limit
        val tail = jobs.single { it.endOfSpeech }
        assertEquals(frameIds(tail), frameIds(f))
        assertTrue(jobs.indexOf(f) > jobs.indexOf(tail))
    }

    @Test
    fun askedEarlyAFinishedAnswerFinalizesAtThePauseWithTheSameAudio() {
        val pattern = listOf(true to 1504, false to 3000)
        val atPause = ArrayList<Long>(); val early = ArrayList<Long>()
        val a = timedJudge(answer = false, latencyMs = 200, pattern = pattern, times = atPause)
        val b = timedJudge(answer = false, latencyMs = 200, pattern = pattern, askSilenceMs = 224, times = early)
        val fa = a.indexOfFirst { it.isFinal }; val fb = b.indexOfFirst { it.isFinal }
        assertEquals(frameIds(a[fa]), frameIds(b[fb]))
        // asked at the pause: final ~200 ms after it; asked at 224 ms: the answer is there when the pause (416 ms) is
        assertTrue("${atPause[fa]}", atPause[fa] >= 1504 + 416 + 200)
        assertTrue("${early[fb]}", early[fb] <= 1504 + 448)
    }

    @Test
    fun askedEarlyWithoutAnAnswerTheFinalComesWhenItWouldHave() {
        val pattern = listOf(true to 1504, false to 3000)
        val atPause = ArrayList<Long>(); val early = ArrayList<Long>()
        val a = timedJudge(answer = false, latencyMs = 60_000, pattern = pattern, times = atPause)
        val b = timedJudge(answer = false, latencyMs = 60_000, pattern = pattern, askSilenceMs = 224, times = early)
        assertEquals(atPause[a.indexOfFirst { it.isFinal }], early[b.indexOfFirst { it.isFinal }])
    }

    private fun sig(jobs: List<AsrJob>) = jobs.map { "${it.segmentId}/${it.revision}/${it.isFinal}/${it.endOfSpeech}/${it.samples.size}" }

    @Test
    fun askingEarlyChangesNothingNearAForcedCutOrWithAQuestionOpen() {
        // a pause just before the forced cut: the cut question and then the pause, as when asked at the pause
        for (answer in listOf(true, false)) {
            val near = listOf(true to 4768, false to 512, true to 1504, false to 3000)
            assertEquals(sig(timedJudge(answer, 64, near, maxUtteranceMs = 5000)),
                sig(timedJudge(answer, 64, near, maxUtteranceMs = 5000, askSilenceMs = 224)))
            // a forced-cut question still open at a short silence: it is not replaced by an early question
            val open = listOf(true to 5408, false to 320, true to 1504, false to 3000)
            assertEquals(sig(timedJudge(answer, 640, open, maxUtteranceMs = 5000)),
                sig(timedJudge(answer, 640, open, maxUtteranceMs = 5000, askSilenceMs = 224)))
        }
    }

    @Test
    fun askedEarlyWithAShortWaitTheFinalStillComesWhenItWouldHave() {
        val pattern = listOf(true to 1504, false to 3000)
        val atPause = ArrayList<Long>(); val early = ArrayList<Long>()
        val a = timedJudge(answer = false, latencyMs = 60_000, pattern = pattern, hintWaitMs = 96, times = atPause)
        val b = timedJudge(answer = false, latencyMs = 60_000, pattern = pattern, hintWaitMs = 96, askSilenceMs = 224, times = early)
        assertEquals(atPause[a.indexOfFirst { it.isFinal }], early[b.indexOfFirst { it.isFinal }])
    }

    @Test
    fun askedEarlyASilenceShorterThanThePauseIsNoCut() {
        // 320 ms of silence: asked about at 224 ms and answered "finished", but the speaker went on before 400 ms
        val jobs = timedJudge(answer = false, latencyMs = 64, askSilenceMs = 224,
            pattern = listOf(true to 1504, false to 320, true to 1504, false to 3000))
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
        assertTrue(jobs.count { it.endOfSpeech } >= 2)
    }

    // A pause candidate whose "finished" answer arrives after the speaker went on: the sentence before the pause is
    // still done, so it is finalized there and the speech after the pause becomes the next segment.
    private val resumedBeforeAnswer = listOf(true to 1504, false to 512, true to 2496, false to 3000)

    @Test
    fun finishedAnswerAfterSpeechResumedStillCutsAtThatPause() {
        val jobs = timedJudge(answer = false, latencyMs = 650, pattern = resumedBeforeAnswer)
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        val tail = jobs.first { it.endOfSpeech && it.segmentId == 1L }
        assertEquals(frameIds(tail), frameIds(finals[0]))
        // the final comes before the speaker stopped again: right after the answer, while segment 2 is being spoken
        assertTrue(jobs.indexOf(finals[0]) < jobs.indexOfFirst { it.endOfSpeech && it.segmentId == 2L })
        // nothing lost or doubled: the second final starts right after the first
        assertEquals(frameIds(finals[0]).last() + 1, frameIds(finals[1]).first())
    }

    @Test
    fun unfinishedAnswerAfterSpeechResumedKeepsOneSegment() {
        val jobs = timedJudge(answer = true, latencyMs = 650, pattern = resumedBeforeAnswer)
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun aFinishedAnswerTooLateAfterSpeechResumedIsNotUsed() {
        // the answer comes 2.5 s after the candidate, past its validity (2 x hintWaitMs): the old boundary is dropped
        val jobs = timedJudge(answer = false, latencyMs = 2500, pattern = listOf(true to 1504, false to 512, true to 2496, false to 400))
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
    }

    @Test
    fun aNewerForcedCutQuestionDropsAnOlderPauseCandidateTheSpeakerTalkedPast() {
        // 4 s speech, a pause (candidate), speech again; the forced-cut question at 5 s is newer. The pause's late
        // "finished" (1.3 s after it) must not cut while the forced-cut question is still open.
        val jobs = timedJudge(answer = false, latencyMs = 1300, maxUtteranceMs = 5000,
            pattern = listOf(true to 4000, false to 512, true to 1504, false to 3000))
        val firstFinal = jobs.first { it.isFinal }
        val pause = jobs.first { it.endOfSpeech }
        assertTrue(frameIds(firstFinal) != frameIds(pause))
    }

    @Test
    fun judgeSlowerThanTheBudgetFinalizesAtTheBudget() {
        val jobs = timedJudge(answer = true, latencyMs = 5000, pattern = listOf(true to 1504, false to 1700, true to 1504, false to 3000))
        assertEquals(listOf(1L, 2L), jobs.filter { it.isFinal }.map { it.segmentId })
    }

    @Test
    fun longSilenceDuringAHoldProducesNoSilentFinalAndLosesNothing() {
        // utterance near the forced-cut limit, then a held pause that crosses maxUtteranceMs, then more speech
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> true })
        val total = run(listOf(true to 2496, false to 1600, true to 1504, false to 1600), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        val ids = finals.flatMap(::frameIds)
        // trailing silence of a final is dropped as usual; every speech frame is kept exactly once, in order
        assertEquals(ids.sorted().distinct(), ids)
        assertTrue(ids.containsAll((1..78).toList() + (129..175).toList()))
        assertEquals(2, finals.size)
    }

    /** A forced cut is a candidate too: an unfinished head is carried into the next chunk, up to the hard limit. */
    @Test
    fun forcedCutWithUnfinishedAnswerCarriesTheHeadIntoTheNextChunk() {
        // 3 s speech, short pause (no natural final), 2 s speech; normal cut limit 3 s, hard limit 6 s
        val jobs = timedJudge(answer = true, latencyMs = 100, maxUtteranceMs = 3000,
            pattern = listOf(true to 2496, false to 256, true to 2016, false to 3000))
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertTrue(finals[0].samples.size <= 6000 * 16 + 512)
    }

    @Test
    fun forcedCutNeverExceedsTheHardLimit() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> true })
        val total = run(listOf(true to 20_000), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.all { it.samples.size <= 6000 * 16 + 512 })
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun aForcedCutHoldMadeStaleLaterCutsAtOnceWhereItWouldHave() {
        // "unfinished" first; once the segmenter has taken it the hint turns to "finished" (a 429 made it stale)
        val jobs = ArrayList<AsrJob>()
        var asked = 0
        // "unfinished" while the segmenter takes it (a few frames), "finished" from then on
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> ++asked <= 3 })
        val total = run(listOf(true to 3600), s)
        val finals = jobs.filter { it.isFinal }
        // cut soon after it turned stale (3 s + a few frames), not at the hard limit (6 s)
        assertEquals(1, finals.size)
        assertTrue(finals[0].samples.size <= 3000 * 16 + 512)
        s.flush()
        // every frame exactly once, in order
        assertEquals((1..total).toList(), jobs.filter { it.isFinal }.flatMap(::frameIds))
    }

    @Test
    fun aPauseHoldThatTookOverAndTurnsStaleCutsAtItsOwnPlace() {
        // forced cut at 3 s held ("unfinished") → a pause candidate takes over, also held, speech resumes → both turn
        // stale (a 429): cut at once, at the pause candidate, not at the old forced cut and not at the hard limit
        val jobs = ArrayList<AsrJob>()
        val stale = java.util.concurrent.atomic.AtomicBoolean(false)
        var pauseCut: AsrJob? = null
        var cutCandidates = 0
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech && !it.isFinal && ++cutCandidates == 2) pauseCut = it },
            maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> !stale.get() })
        var idx = 0
        fun feed(speech: Boolean, ms: Int) = repeat(ms / 32) {
            idx++; s.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, idx * 32L)
        }
        feed(true, 3200); feed(false, 512); feed(true, 320)
        assertTrue("a pause candidate took over", pauseCut != null)
        assertTrue(jobs.none { it.isFinal })
        stale.set(true)
        feed(true, 64)
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertEquals(frameIds(pauseCut!!).last(), frameIds(finals[0]).last())
        feed(true, 320); s.flush()
        assertEquals((1..idx).toList(), jobs.filter { it.isFinal }.flatMap(::frameIds))
    }

    @Test
    fun aForcedCutAfterAPauseHoldSupersedesItAlsoWhenBothTurnStale() {
        // a pause candidate held (accepted after speech resumed) → a newer forced cut asked, its answer not in → a 429
        // makes everything stale: the cut is the forced cut's, never the older pause's
        val jobs = ArrayList<AsrJob>()
        val stale = java.util.concurrent.atomic.AtomicBoolean(false)
        val cands = ArrayList<AsrJob>()
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech && !it.isFinal) cands.add(it) },
            maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, rev ->
                when {
                    stale.get() -> false
                    cands.firstOrNull()?.revision == rev -> true // the pause: "unfinished"
                    else -> null // the forced cut: no answer yet
                }
            })
        var idx = 0
        fun feed(speech: Boolean, ms: Int) = repeat(ms / 32) {
            idx++; s.accept(FloatArray(frame) { idx.toFloat() }, if (speech) 0.9f else 0.1f, idx * 32L)
        }
        feed(true, 1216); feed(false, 416); feed(true, 1600)
        assertEquals(2, cands.size) // the pause, then the forced cut
        assertTrue(jobs.none { it.isFinal })
        stale.set(true)
        feed(true, 64)
        val finals = jobs.filter { it.isFinal }
        assertEquals(1, finals.size)
        assertEquals(frameIds(cands[1]).last(), frameIds(finals[0]).last())
        feed(true, 320); s.flush()
        assertEquals((1..idx).toList(), jobs.filter { it.isFinal }.flatMap(::frameIds))
    }

    @Test
    fun aStaleForcedCutAnswerNeverCutsAtTheOldPlace() {
        // forced-cut candidate (unfinished) → long utterance continues → a pause candidate → speech resumes →
        // the judge now says "finished" about the *old* cut: that must not cut back at the old position
        val jobs = ArrayList<AsrJob>()
        var firstCut: AsrJob? = null
        var pauseSeen = false
        val s = Segmenter({ jobs.add(it); if (it.endOfSpeech) { if (firstCut == null) firstCut = it else pauseSeen = true } },
            maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { seg, rev ->
                val c = firstCut
                when {
                    c != null && seg == c.segmentId && rev == c.revision -> !pauseSeen // unfinished first, "finished" later
                    else -> true // keep every later candidate open
                }
            })
        // 3 s speech (cut candidate at 3 s), 0.6 s more, 0.5 s pause (candidate), then 1 s speech
        run(listOf(true to 3008, true to 608, false to 512, true to 1024, false to 1600), s)
        s.flush()
        val cut = firstCut!!
        val finals = jobs.filter { it.isFinal }
        assertTrue("no final may end at the old cut", finals.none { frameIds(it).last() == frameIds(cut).last() })
    }

    @Test
    fun aPauseCandidateClosesAnUnansweredForcedCutQuestion() {
        // the judge never answers: forced-cut question at 3 s → pause candidate → speech resumes. One question per
        // utterance: no new forced-cut question may be asked before the hard limit.
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> null })
        // pause candidate at ~3.6 s, inside the 900 ms answer window of the 3 s question; ends at 4.7 s (< 6 s hard limit)
        run(listOf(true to 3008, true to 208, false to 512, true to 1024), s)
        assertEquals("forced-cut question + pause candidate only", 2, jobs.count { it.endOfSpeech })
        assertTrue("the unanswered old question must not cut either", jobs.none { it.isFinal })
    }

    @Test
    fun draftsKeepComingWhileAJudgedForcedCutIsHeldOpen() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900,
            holdHint = { _, _ -> true })
        run(listOf(true to 5504), s)
        // past the 3 s cut point the speaker is still talking: the live caption must keep updating
        val lateDrafts = jobs.filter { !it.isFinal && !it.endOfSpeech && it.samples.size > 3500 * 16 }
        assertTrue("drafts after the cut point: ${lateDrafts.size}", lateDrafts.size >= 2)
    }

    @Test
    fun holdingLosesNoFrameAndForcedCutStillApplies() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 3000, minFinalMs = 1200, maxHoldSilenceMs = 1500, hintWaitMs = 900, holdHint = { _, _ -> true })
        val total = run(listOf(true to 640, false to 608, true to 640, false to 608, true to 6016), s)
        s.flush()
        val finals = jobs.filter { it.isFinal }
        assertTrue(finals.size >= 2)
        // with a judge saying "unfinished", a forced cut waits up to the hard limit (2 × maxUtteranceMs)
        assertTrue(finals.all { it.samples.size <= 6000 * 16 + frame })
        assertEquals((1..total).toList(), finals.flatMap(::frameIds))
    }

    @Test
    fun flushFinalizesAHeldFragment() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500)
        run(listOf(true to 640, false to 608), s)
        assertTrue(jobs.none { it.isFinal })
        s.flush()
        assertEquals(1, jobs.count { it.isFinal })
    }

    @Test
    fun anUtteranceWithoutAJudgeIsCutAsWithoutJev() {
        // Jev configured for the session but paused for segment 1 (no key), back for segment 2
        val asked = ArrayList<Long>()
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, minFinalMs = 1200, maxHoldSilenceMs = 1500, maxUtteranceMs = 5_000, hintWaitMs = 1_000,
            holdHint = { _, _ -> null }, judged = { seg -> asked += seg; seg != 1L }, plainMaxUtteranceMs = 7_000)
        // segment 1: 1.5 s of speech, a pause: final at the normal end silence, no candidate for Jev
        run(listOf(true to 1504, false to 608), s)
        assertTrue(jobs.none { it.endOfSpeech })
        assertEquals(listOf(1L), jobs.filter { it.isFinal }.map { it.segmentId })
        // segment 2 (judged): the same pause emits a candidate and waits for its answer instead
        run(listOf(true to 1504, false to 608), s)
        assertTrue(jobs.any { it.segmentId == 2L && it.endOfSpeech })
        assertEquals(1, jobs.count { it.isFinal })
        assertEquals(listOf(1L, 2L), asked) // asked once per utterance, when it starts
    }

    @Test
    fun anUnjudgedUtteranceUsesThePlainLengthLimit() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 5_000, hintWaitMs = 1_000, judged = { false }, plainMaxUtteranceMs = 7_000)
        run(listOf(true to 6400), s)
        assertTrue(jobs.none { it.isFinal }) // 6.4 s: past the Jev limit (5 s), still under the plain one (7 s)
        run(listOf(true to 800), s)
        assertEquals(1, jobs.count { it.isFinal })
        assertTrue(jobs.none { it.endOfSpeech })
    }

    // --- a long utterance ends at a short pause, without waiting for the length limit ---

    @Test
    fun aLongUtteranceEndsAtAShortPauseThere() {
        val jobs = ArrayList<AsrJob>()
        var fed = 0
        val finalAtFrame = ArrayList<Int>()
        val s = Segmenter({ jobs.add(it); if (it.isFinal) finalAtFrame += fed }, maxUtteranceMs = 7_000, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 256)
        // 3.52 s speech (110 frames), a 288 ms pause (short of the 400 ms end silence), then 3 s more
        for ((speech, ms) in listOf(true to 3520, false to 288, true to 3008, false to 608)) repeat(ms / 32) {
            fed++; s.accept(FloatArray(frame) { fed.toFloat() }, if (speech) 0.9f else 0.1f, fed * 32L)
        }
        val finals = jobs.filter { it.isFinal }
        assertEquals(listOf(1L, 2L), finals.map { it.segmentId })
        // the first final comes when the pause reaches 256 ms (frame 118), not 7 s in when a forced cut would go back to it
        assertEquals(110 + 256 / 32, finalAtFrame[0])
        // it keeps the speech and ~100 ms of the pause, as a final at the normal end silence does
        assertEquals((1..113).toList(), frameIds(finals[0]))
    }

    @Test
    fun aShortPauseEarlyInTheUtteranceDoesNotEndIt() {
        val jobs = ArrayList<AsrJob>()
        val s = Segmenter(jobs::add, maxUtteranceMs = 7_000, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 256)
        // the same short pause 2 s in: the sentence is likely still going, it stays one utterance
        run(listOf(true to 2016, false to 288, true to 1504, false to 608), s)
        assertEquals(1, jobs.count { it.isFinal })
    }

    @Test
    fun withAJudgeTheShortPauseIsLeftToIt() {
        val jobs = ArrayList<AsrJob>()
        // Jev asks about pauses itself (askSilenceMs); no answer here, so the utterance just goes on
        val s = Segmenter(jobs::add, minFinalMs = 400, hintWaitMs = 1_000, earlyCutAfterMs = 3_000, earlyCutSilenceMs = 256)
        run(listOf(true to 3520, false to 288, true to 1504), s)
        assertTrue(jobs.none { it.isFinal })
    }
}
