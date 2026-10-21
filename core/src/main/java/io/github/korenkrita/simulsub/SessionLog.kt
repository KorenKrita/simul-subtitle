package io.github.korenkrita.simulsub

import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * One JSONL file per capture session, for offline analysis (term extraction, tuning): every ASR result, every MT call
 * with its full prompt and raw output, every Jev request with its raw response, and every caption shown.
 * Each line is one JSON object with "ev" (event type) and "t" (monoMs). No audio, no API keys.
 *
 * Writes happen on one background thread so the pipeline never waits on storage. [write] never blocks: the queue is
 * bounded, and a full queue or a failed write sets [failed], calls [onFail] once (the overlay shows it), and later
 * events are dropped. The file is written as `….jsonl.part` and renamed to `.jsonl` only by a [close] that wrote
 * everything, so an interrupted, failed or still-running session is never mistaken for a complete one.
 */
class SessionLog(
    dir: File,
    capacity: Int = 10_000,
    open: (File) -> Writer = { it.bufferedWriter() },
    private val onFail: (String) -> Unit = {},
) {
    private val done: File
    /** the file being written; after a successful [close] the complete file is at [file] */
    private val part: File
    init {
        dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var i = 0
        synchronized(NAMES) {
            while (true) {
                val name = "session-$ts" + (if (i == 0) "" else "-$i")
                val d = File(dir, "$name.jsonl"); val p = File(dir, "$name.jsonl$PART")
                // created and marked active under one lock: a listing (also under it) never sees it unmarked
                if (!d.exists() && p.createNewFile()) { done = d; part = p; ACTIVE.add(p); break }
                i++
            }
        }
    }
    @Volatile var file: File = part
        private set
    @Volatile var failed: String? = null
        private set
    private val queue = LinkedBlockingQueue<Any>(capacity)
    /** set by [close]; the writer stops once the queue is empty, so no lost or cleared end marker can strand it */
    @Volatile private var closing = false
    private val thread = Thread({
        var w: Writer? = null
        try {
            w = open(part)
            while (true) {
                val e = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (e == null) { if (closing || failed != null) break else continue }
                w.write(e as String); w.write("\n")
                if (queue.isEmpty()) w.flush() // a crash loses at most what is still queued
            }
            w.flush()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        } finally {
            runCatching { w?.close() }.onFailure { fail(it.toString()) }
            // nothing writes this file any more; a complete close() renames it first (under NAMES)
            if (closing && failed != null) ACTIVE.remove(part)
        }
    }, "jpsub-log").apply { isDaemon = true; start() }

    @Synchronized private fun fail(why: String) {
        if (failed != null) return
        failed = why
        queue.clear() // the writer is dead or the log is already incomplete: free the memory
        onFail(why)
    }

    fun write(event: Map<String, Any?>) {
        if (failed != null || closing) return
        if (!queue.offer(Json.encode(mapOf("t" to monoMs()) + event))) fail("日志写入跟不上，后面的记录已丢弃")
    }

    /**
     * Writes everything queued, then closes the file. Call once, after the pipeline workers have exited.
     * @return true only if every event was written and the file is complete; otherwise [failed] says why.
     */
    fun close(timeoutMs: Long = 3_000): Boolean {
        val ok = finish(timeoutMs)
        // a writer still stuck on the disk keeps the file protected; it releases it itself when it exits
        if (!thread.isAlive) ACTIVE.remove(part)
        return ok
    }

    private fun finish(timeoutMs: Long): Boolean {
        closing = true
        thread.join(timeoutMs)
        if (thread.isAlive) fail("关闭日志超时，文件可能不完整") // the writer still exits once the disk returns
        if (failed != null) return false
        // only this process writes the log dir: naming and finishing under one lock means no name is ever reused
        val target = synchronized(NAMES) {
            var t = done
            var i = 1
            while (t.exists()) t = File(done.parentFile, done.name.removeSuffix(".jsonl") + "-x${i++}.jsonl")
            t.takeIf { part.renameTo(it) }?.also { ACTIVE.remove(part) }
        } ?: run { fail("无法完成日志文件"); return false }
        file = target
        return true
    }

    companion object {
        private const val PART = ".part"
        private val NAMES = Any()
        fun dir(root: File) = File(root, "logs")
        /**
         * `.part` files whose session may still write them (running, closing, or a writer stuck on the disk): they grow
         * and are renamed on close, so they must not be shared (a shared URI would point at a file that is about to move).
         */
        private val ACTIVE: MutableSet<File> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // list/isActive take the lock a new log is created and marked under, so a listed `.part` that is still being
        // written is always reported active (it stays so until renamed, also under the lock, or its writer exits)
        fun isActive(f: File) = synchronized(NAMES) { f in ACTIVE }
        /** for change detection on the home screen */
        fun activeFiles(): Set<File> = synchronized(NAMES) { ACTIVE.toSet() }
        /** Newest first; includes unfinished `.part` files (see [complete]). */
        fun list(root: File): List<File> = synchronized(NAMES) {
            dir(root).listFiles { f -> f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl$PART") }
                ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }) ?: emptyList()
        }
        /** false for a session that is still running, failed, or was interrupted */
        fun complete(f: File) = f.name.endsWith(".jsonl")
        /** Deletes one listed log unless a session may still write it. @return true if it is gone */
        fun delete(f: File): Boolean = synchronized(NAMES) { f !in ACTIVE && (f.delete() || !f.exists()) }
        /** Deletes every log no session may still write. @return how many logs are left (running or undeletable) */
        fun deleteAll(root: File): Int = synchronized(NAMES) { list(root).count { !delete(it) } }
    }
}

/**
 * The finished sentences of one session as plain text, the source and its translation, for reading and sharing. Kept
 * apart from [SessionLog]: its own switch, nothing for debugging, and never renamed, so it can be read or shared while
 * it is written. A session that finished no sentence leaves no file. A failed write stops it ([failed]).
 */
class Transcript private constructor(val file: File, private val out: Writer) {
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var sentences = 0
    private var lastSeg = -1L
    @Volatile var failed = false; private set

    /** One finished sentence; a segment is written once. Any thread. */
    @Synchronized fun sentence(seg: Long, source: String, translation: String) {
        if (seg <= lastSeg) return
        lastSeg = seg
        if (put("${clock.format(Date())}\n$source\n$translation\n\n")) sentences++
    }


    private fun put(s: String): Boolean {
        if (failed) return false
        try { out.write(s); out.flush(); return true } catch (_: java.io.IOException) { failed = true; return false }
    }

    @Synchronized fun close() {
        runCatching { out.close() }
        synchronized(NAMES) {
            if (writing == file) writing = null
            if (sentences == 0) file.delete()
        }
    }

    companion object {
        private val NAMES = Any()
        /** the file a running session writes: not deleted under it */
        private var writing: File? = null
        fun dir(root: File) = File(root, "transcripts")

        /** @throws java.io.IOException when the file cannot be made */
        fun open(root: File, startedAt: Date = Date()): Transcript {
            val dir = dir(root).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(startedAt)
            val f = synchronized(NAMES) {
                var i = 0
                var f: File
                while (true) {
                    f = File(dir, "同传字幕-$ts${if (i == 0) "" else "-$i"}.txt")
                    if (f.createNewFile()) break
                    i++
                }
                f.also { writing = it }
            }
            val out = try { f.bufferedWriter() } catch (e: java.io.IOException) { synchronized(NAMES) { writing = null; f.delete() }; throw e }
            val head = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(startedAt)
            return Transcript(f, out).also { it.put("同传字幕 $head 开始\n\n") }
        }

        /** Newest first. */
        fun list(root: File): List<File> =
            dir(root).listFiles { f -> f.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: emptyList()
        fun isWriting(f: File) = synchronized(NAMES) { f == writing }
        /** @return true if it is gone; false for the file a session is writing */
        fun delete(f: File): Boolean = synchronized(NAMES) { f != writing && (f.delete() || !f.exists()) }
        /** @return how many are left (being written or undeletable) */
        fun deleteAll(root: File): Int = list(root).count { !delete(it) }
    }
}
