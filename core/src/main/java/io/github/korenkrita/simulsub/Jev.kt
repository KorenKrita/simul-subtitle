package io.github.korenkrita.simulsub

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

// TypeSafe Jev (System One decision model) client and the two questions the pipeline asks it.
// Pure Kotlin + OkHttp so the Mac harness (tools/mac-e2e) runs the same code.

/**
 * [needsKey] false: no Authorization header is sent (Zen rejects a wrong key with 401 but answers without one).
 * [CUSTOM]: the user's own address and model ([JevCustom]); its key is optional, sent only when one is saved.
 */
enum class JevProvider(val label: String, val url: String, val model: String, val needsKey: Boolean = true, val note: String) {
    // free and keyless, but undocumented: it may get rate limits or a login at any time (tested 2026-10-10)
    ZEN("OpenCode Zen", "https://opencode.ai/zen/v1/systemone", "jev-1.13-free", needsKey = false, note = "免费，不用 key"),
    OFFICIAL("TypeSafe 官方", "https://api.typesafe.ai/v1/systemone", "jev-latest", note = "需要 key"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/systemone", "~typesafe/jev-latest", note = "需要 key"),
    CUSTOM("自定义", "", "", needsKey = false, note = "自己填地址和模型"),
}

/**
 * The two APIs that serve Jev. They take the same request and give the same answers for the questions asked here;
 * only the path differs (OpenRouter Decisions API: choice's confidence is optional).
 */
enum class JevProtocol(val label: String, val path: String) {
    SYSTEMONE("TypeSafe System One", "/v1/systemone"),
    DECISIONS("OpenRouter Decisions", "/alpha/decisions"),
}

/** The user's own Jev service, saved as one value so a session never pairs one address with another's model. */
class JevCustom(val protocol: JevProtocol, val url: String, val model: String) {
    /**
     * (error text, null) or (null, the URL requested). An address already ending in /systemone or /decisions is used
     * as is (a proxy such as CLIProxyAPI serves decisions at …/decisions); otherwise the protocol's path is added.
     */
    fun endpoint(): Pair<String?, String?> = ByokTranslator.httpUrl(url) { path ->
        if (path.endsWith("/systemone") || path.endsWith("/decisions")) path else path + protocol.path
    }
    fun encode() = Json.encode(mapOf("protocol" to protocol.name, "url" to url, "model" to model))
    companion object {
        fun decode(s: String?): JevCustom? = runCatching {
            val m = Json.parse(s ?: return null) as Map<*, *>
            JevCustom(JevProtocol.valueOf(m["protocol"] as String), m["url"] as String, m["model"] as String)
        }.getOrNull()
    }
}

/**
 * One request with everything the session log needs. [answer] is the parsed `answers.x`, null on any failure.
 * [limit]: the service limited requests (HTTP 429), now or before (then nothing was sent, [error] "paused").
 * [gen]: the service's limit generation when the request was let out (see [Judge.limitedAfter]); -1 = not sent.
 */
class JevCall(val request: String, val status: Int, val response: String?, val ms: Long, val error: String?, val answer: Map<*, *>?,
              val limit: JevLimit? = null, val gen: Int = -1) {
    val noul: Double? get() = (answer?.get("noul") as? Number)?.toDouble()
    val choice: String? get() = answer?.get("choice") as? String
    // the Decisions API may leave confidence out; then the chosen class's probability
    val confidence: Double? get() = (answer?.get("confidence") as? Number)?.toDouble()
        ?: ((answer?.get("probabilities") as? Map<*, *>)?.get(choice) as? Number)?.toDouble()
}

/**
 * A 429 from a Jev service: no request is sent to it before [untilMs] (the Jev client's monotonic clock). [resumeAt]
 * is the wall time its Retry-After named, null when it named none (then a short backoff, retried by the next request).
 * [free]: OpenCode Zen's free usage limit (FreeUsageLimitError). [gen]: the generation this 429 started.
 */
class JevLimit(val untilMs: Long, val resumeAt: Long?, val free: Boolean, val gen: Int = 0) {
    companion object {
        /** [Judge.paused] while a 429 is being installed: its end is not known yet (never stored as a result's limit) */
        val INSTALLING = JevLimit(Long.MAX_VALUE, null, false, -1)
    }
}


/** What the pipeline asks; an interface so tests can answer without a network. */
interface Judge {
    /** Is the speech-recognition text that ended at a pause an unfinished sentence? */
    fun unfinished(text: String): JevCall
    /** What is wrong with this translation, if anything? */
    fun mistranslation(ja: String, zh: String): JevCall
    /** Abort the request in progress (stop). */
    fun cancel()
    /** The service's latest installed limit, also one that has ended (never a 429 still being installed). */
    fun lastLimit(): JevLimit? = null
    /**
     * [onChange] runs (on a network thread, no lock held) whenever a 429 of this judge's service was installed, by any
     * client of that service: what [paused] and [lastLimit] say changed. Not a request result. Close to stop.
     */
    fun watch(onChange: () -> Unit): AutoCloseable = AutoCloseable {}
    /** The service limited requests and the pause has not ended: a question now is not sent. */
    fun paused(): JevLimit? = null
    /**
     * The service sent a 429 after a request of generation [gen] was let out: that request's answer is from before
     * the limit and is not acted on, also once a short pause is over.
     */
    fun limitedAfter(gen: Int): Boolean = false
}

/** A request held back at the moment it would be sent (see [Calls.post] admit). */
class PausedException : IOException("paused")

class Jev(
    private val provider: JevProvider,
    private val apiKey: String,
    private val deadlineMs: Long = 2_000,
    /** tests point this at a local server */
    private val url: String = provider.url,
    /** [JevProvider.CUSTOM]: the user's model */
    private val model: String = provider.model,
    /** tests: runs after the request is registered for cancel() and before it is executed */
    private val beforeSend: () -> Unit = {},
    /** the session's languages; Japanese→Chinese uses the wording tuned on the test sets */
    private val source: Lang? = Lang.JA,
    private val target: Target = Target.ZH,
    /** monotonic ms for pauses; the app passes one that keeps counting in deep sleep (elapsedRealtime) */
    private val clock: () -> Long = ::monoMs,
    private val wall: () -> Long = System::currentTimeMillis,
    dns: okhttp3.Dns = okhttp3.Dns.SYSTEM,
) : Judge {
    private val calls = Calls(deadlineMs, dns)
    // a custom service may need no key (a local proxy): no header then
    private val sendsKey = provider.needsKey || (provider == JevProvider.CUSTOM && apiKey.isNotEmpty())
    private val unfinishedQ = unfinishedQuestion(source)
    private val mistranslationQ = mistranslationQuestion(source, target)
    /** the pause is per service and credentials, so a rebuilt setup (new client) does not end it */
    private val svc = SERVICES.computeIfAbsent("$url\n$apiKey") { Service() }

    override fun unfinished(text: String) = ask(mapOf("current" to text), unfinishedQ)
    override fun mistranslation(ja: String, zh: String) = ask(mapOf("ja" to ja, "zh" to zh), mistranslationQ)

    override fun cancel() = calls.cancel()
    // a 429 still being installed already counts (its end is not known yet)
    override fun paused(): JevLimit? = synchronized(svc) {
        svc.limit?.takeIf { clock() < it.untilMs } ?: if (svc.installing > 0) JevLimit.INSTALLING else null
    }
    override fun lastLimit(): JevLimit? = synchronized(svc) { svc.limit }
    override fun watch(onChange: () -> Unit): AutoCloseable {
        synchronized(svc) { svc.watchers += onChange }
        return AutoCloseable { synchronized(svc) { svc.watchers -= onChange } }
    }
    override fun limitedAfter(gen: Int): Boolean = synchronized(svc) { gen >= 0 && svc.gen > gen }

    private fun ask(state: Map<String, String>, question: Map<String, Any>): JevCall {
        val body = Json.encode(mapOf("model" to model, "state" to state, "questions" to mapOf("x" to question)))
        // an illegal header value would make the HTTP library put the key into its exception message
        if (sendsKey && !validKey(apiKey)) return JevCall(body, 0, null, 0, "bad key", null)
        paused()?.let { return JevCall(body, 0, null, 0, "paused", null, it) }
        val auth = if (sendsKey) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
        // let out only when not paused, checked again right before the request is written (after OkHttp's queue and
        // the connection); the generation it went out with tells a later 429 apart
        val sentGen = java.util.concurrent.atomic.AtomicInteger(-1)
        val admit = { synchronized(svc) {
            if (svc.installing > 0 || svc.limit?.let { clock() < it.untilMs } == true) false else { sentGen.set(svc.gen); true }
        } }
        // the pause starts when the 429's headers arrive. First, at once and under the monitor: the receipt time, a
        // new generation, and "being installed" (nothing is let out from here on). Then, outside it: reading the
        // header and the wall clock, and merging the end in (a later end wins, an older request's answer never
        // shortens or ends a pause). Delay-seconds count from the receipt; an HTTP-date is an instant, turned into
        // the monotonic clock by a wall/monotonic pair read together.
        val myGen = java.util.concurrent.atomic.AtomicInteger(-1)
        val on429 = { retryAfter: String? ->
            val at = synchronized(svc) { svc.gen++; myGen.set(svc.gen); svc.installing++; clock() }
            var limit: JevLimit? = null
            try {
                val w = wall(); val m = clock()
                val v = retryAfter?.trim().orEmpty()
                val secs = if (v.isNotEmpty() && v.all { it in '0'..'9' }) (v.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost(1_000_000_000_000L) else null
                val date = if (secs == null && v.isNotEmpty()) httpDate(v, w) else null
                limit = when {
                    secs != null -> JevLimit(at + secs * 1000, w - (m - at) + secs * 1000, false)
                    date != null -> JevLimit(m + maxOf(0, date - w), date, false)
                    else -> JevLimit(at + BACKOFF_S * 1000, null, false)
                }
            } finally {
                synchronized(svc) {
                    val mine = limit ?: JevLimit(at + BACKOFF_S * 1000, null, false)
                    val old = svc.limit
                    svc.limit = if (old != null && old.untilMs >= mine.untilMs) JevLimit(old.untilMs, old.resumeAt, old.free, svc.gen)
                        else JevLimit(mine.untilMs, mine.resumeAt, false, svc.gen)
                    svc.installing--
                }
            }
            // after the monitor: what the service's pause looks like changed, for every client watching it
            synchronized(svc) { svc.watchers.toList() }.forEach { it() }
        }
        // a deadline that came while the 429 was being installed (its status not yet published): still that 429
        val r = calls.post(url, body, auth, beforeSend, on429 = on429, admit = admit).let {
            if (it.status == 0 && it.error?.startsWith("deadline") == true && myGen.get() >= 0)
                HttpResult(429, null, it.ms, it.error, it.firstContentMs) else it
        }
        if (r.error == "paused") return JevCall(body, 0, null, r.ms, "paused", null, paused())
        val answer = if (r.status == 200 && r.body != null)
            runCatching { ((Json.parse(r.body) as? Map<*, *>)?.get("answers") as? Map<*, *>)?.get("x") as? Map<*, *> }.getOrNull()
        else null
        // a 429 always went through the headers' callback, which installed it; the body only adds whether it is the
        // free limit, to its own generation
        val limit = if (r.status != 429) null else synchronized(svc) {
            val free = isZenFreeLimit(provider, r.body)
            svc.limit?.let { l -> if (free && l.gen == myGen.get() && !l.free) JevLimit(l.untilMs, l.resumeAt, true, l.gen).also { svc.limit = it } else l }
        }
        // a 200 is a success only when it answers the question asked (a number for noul, a known class for choice)
        val usable = answer?.takeIf { a ->
            when (question["type"]) {
                "noul" -> (a["noul"] as? Number)?.toDouble()?.let { it in 0.0..1.0 } == true
                "choice" -> (a["choice"] as? String).let { c ->
                    c in ((question["criteria"] as? Map<*, *>)?.keys ?: emptySet<Any>()) &&
                        ((a["confidence"] ?: (a["probabilities"] as? Map<*, *>)?.get(c)) as? Number)?.toDouble()?.let { it in 0.0..1.0 } == true
                }
                else -> true
            }
        }
        // a server may echo the key back (also JSON-escaped); the session log is shareable
        return JevCall(body, r.status, r.body?.let { redact(it, apiKey) }, r.ms,
            r.error ?: if (usable == null) "HTTP ${r.status}" else null, usable, limit, sentGen.get())
    }

    companion object {
        /** Printable ASCII only: a valid bearer token, and nothing the HTTP library would reject (and echo). */
        fun validKey(k: String) = k.isNotEmpty() && k.all { it in '!'..'~' }

        /**
         * 测试连接 of a custom service: one real question (the sentence-end one), with time to spare. Returns (ok, the
         * line the settings show). Blocks; call it off the UI thread.
         */
        fun test(c: JevCustom, key: String, clock: () -> Long = ::monoMs): Pair<Boolean, String> {
            val url = c.endpoint().let { (bad, u) -> u ?: return false to (bad ?: "地址格式不对") }
            if (c.model.isEmpty()) return false to "还没填模型"
            // [clock]: the session's (SessionHost.clock), as a 429 pauses the service for both
            val r = Jev(JevProvider.CUSTOM, key, 15_000, url = url, model = c.model, clock = clock).unfinished("今日はいい天気ですね")
            return when {
                r.noul != null -> true to "连上了：${r.ms} ms，回答正常"
                r.error == "bad key" -> false to "key 里有空格、换行或特殊字符，请重新复制"
                r.error == "paused" -> false to "这个服务刚限过速（HTTP 429），稍后再试"
                r.status == 0 -> false to "连不上：${r.error}"
                r.status == 200 -> false to "服务回了 200，但不是 Jev 的回答：${r.response.orEmpty().take(200)}"
                else -> false to "HTTP ${r.status}：${r.response.orEmpty().take(200)}"
            }
        }
        /**
         * One Jev service with its credentials: its pause, its limit generation, and how many 429s are being installed
         * (nothing is let out meanwhile). Guarded by itself.
         */
        private class Service {
            var gen = 0; var limit: JevLimit? = null; var installing = 0
            /** told after a 429 was installed (any client's request), no lock held; see [Judge.watch] */
            val watchers = LinkedHashSet<() -> Unit>()
        }
        /** by service and credentials, for the whole process (a new session still waits) */
        private val SERVICES = java.util.concurrent.ConcurrentHashMap<String, Service>()
        /** a 429 without a usable Retry-After: tried again after this */
        const val BACKOFF_S = 60L

        /**
         * Seconds a Retry-After value ([h]: delay-seconds or an HTTP-date) asks to wait: 0 for "now" or a time already
         * past (tried again at once), null when there is none or it cannot be read (then [BACKOFF_S]).
         */
        fun retrySeconds(h: String?, nowWall: Long): Long? {
            val v = h?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // delay-seconds: digits only (no sign); more than a Long holds is "very long", bounded by the caller
            if (v.all { it in '0'..'9' }) return v.toLongOrNull() ?: Long.MAX_VALUE
            val at = httpDate(v, nowWall) ?: return null
            return maxOf(0, (at - nowWall + 999) / 1000)
        }

        /** The three HTTP-date forms a recipient must read: IMF-fixdate, RFC 850 and asctime (all GMT); impossible dates are none. */
        internal fun httpDate(v: String, nowWall: Long): Long? {
            val us = java.util.Locale.US
            val utc = java.time.ZoneOffset.UTC
            val strict = java.time.format.ResolverStyle.STRICT
            // the exact shapes only (a weekday, seconds, "GMT"); the parsers alone accept looser forms
            if (Regex("""[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT""").matches(v)) runCatching {
                return java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(strict))
                    .toInstant().toEpochMilli()
            }
            // RFC 850's two-digit year: the century whose date is no more than 50 years ahead, the weekday checked after
            // case-sensitive like the other forms (RFC 9110): "Tuesday", "Oct", "GMT"
            Regex("""([A-Z][a-z]+), (\d{2})-([A-Z][a-z]{2})-(\d{2}) (\d{2}:\d{2}:\d{2}) GMT""").matchEntire(v)?.let { m ->
                val (wd, d, mon, yy, time) = m.destructured
                val fmt = java.time.format.DateTimeFormatterBuilder()
                    .appendPattern("dd-MMM-uuuu HH:mm:ss").toFormatter(us).withResolverStyle(strict)
                val limit = java.time.Instant.ofEpochMilli(nowWall).atZone(utc).plusYears(50)
                val century = limit.year / 100 * 100
                val t = runCatching {
                    java.time.LocalDateTime.parse("$d-$mon-${century + yy.toInt()} $time", fmt).let {
                        if (it.atZone(utc).isAfter(limit)) java.time.LocalDateTime.parse("$d-$mon-${century - 100 + yy.toInt()} $time", fmt) else it
                    }
                }.getOrNull() ?: return null
                val day = t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, us)
                return if (day == wd) t.toInstant(utc).toEpochMilli() else null
            }
            // asctime: a one-digit day is padded with a space ("Oct  3")
            if (!Regex("""[A-Z][a-z]{2} [A-Z][a-z]{2} ( \d|\d{2}) \d{2}:\d{2}:\d{2} \d{4}""").matches(v)) return null
            val asctime = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", us).withResolverStyle(strict)
            runCatching { return java.time.LocalDateTime.parse(v.replace("  ", " "), asctime).toInstant(utc).toEpochMilli() }
            return null
        }

        /** OpenCode Zen's own free-usage error (`error.type`), not any body that happens to contain the word */
        fun isZenFreeLimit(provider: JevProvider, body: String?): Boolean = provider == JevProvider.ZEN && body != null &&
            runCatching { ((Json.parse(body) as? Map<*, *>)?.get("error") as? Map<*, *>)?.get("type") == "FreeUsageLimitError" }.getOrDefault(false)

        /**
         * [body] for the log without [secret], as text (a body need not be valid JSON): every spelling of the key, with any
         * of its characters written literally or as a JSON escape (\uXXXX, \" \\ \/), is replaced by ***. A body without
         * the key is returned unchanged (the raw response).
         */
        fun redact(body: String, secret: String): String {
            if (secret.isEmpty()) return body
            val alt = secret.toList().joinToString("") { c: Char ->
                val u = "%04x".format(c.code)
                val esc = when (c) { '"' -> "|\\\\\""; '\\' -> "|\\\\\\\\"; '/' -> "|\\\\/"; else -> "" }
                "(?:${Regex.escape(c.toString())}|\\\\u(?i:$u)$esc)"
            }
            return body.replace(Regex(alt), "***")
        }
        /** Wording tuned on 140 labelled segment pairs without the following text (AUC 0.96, /tmp/jps/case/decision-bench.md). */
        val UNFINISHED = mapOf(
            "type" to "noul",
            "instructions" to "`current` is raw automatic speech-recognition (ASR) output for a Japanese livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling particle or connective (は, が, を, に, で, と, て, けど, から, たら, とか, って), a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a sentence-final form (です, ます, よ, ね, な, じゃない, ありがとう, ごめんね, こんばんは) or is a standalone filler/reaction (はい, えっ, あの, えっと).",
            "criteria" to mapOf(
                "true" to "unfinished or misheard fragment: wait for the next words",
                "false" to "finished: current is a complete utterance or a standalone filler/reaction",
            ),
        )
        /** Same classes as the 37-sentence test; only [FIX_NOTES] keys are acted on. */
        val MISTRANSLATION = mapOf(
            "type" to "choice",
            "instructions" to "`ja` is Japanese VTuber livestream speech (raw speech recognition, may contain misheard words); `zh` is a Chinese machine translation that may be wrong. What is the main problem with `zh`?",
            "criteria" to mapOf(
                "negation" to "zh adds a negation or refusal that ja does not contain",
                "reversal" to "zh reverses the meaning or the positive/negative polarity of ja",
                "literal_metaphor" to "zh translates an idiom or livestream expression literally (e.g. comments 'flowing' rendered as water flowing)",
                "platform_term" to "zh mistranslates a livestream/platform term such as a platform name, superchat, member, gift",
                "untranslated" to "zh leaves Japanese words untranslated",
                "misheard" to "ja itself contains misheard nonsense words and zh translates them literally",
                "hallucination" to "zh adds content that ja does not say",
                "ok" to "zh is an acceptable translation",
            ),
        )

        /**
         * The "unfinished?" question for [source]: Japanese keeps the tuned wording; English and auto (Japanese or
         * English) get the same criteria without the Japanese word lists (not tuned or measured).
         */
        fun unfinishedQuestion(source: Lang?): Map<String, Any> = if (source == Lang.JA) UNFINISHED else mapOf(
            "type" to "noul",
            "instructions" to "`current` is raw automatic speech-recognition (ASR) output for a ${source?.en ?: "Japanese or English"} livestream, ending where the streamer just paused. The ASR often mishears words: a short fragment may be a misrecognized piece of a longer word or phrase, even if it looks like a valid word. Decide whether the speaker's sentence is still unfinished at the end of `current`, so we should wait and join it with what the speaker says next. Unfinished: `current` ends with a dangling preposition, conjunction, article or particle, a modifier waiting for its noun, a word cut in half, or is a short fragment that makes no sense in context and is probably misheard. Finished: `current` ends with a complete sentence or is a standalone filler/reaction.",
            "criteria" to UNFINISHED["criteria"]!!,
        )

        /** The check for a language pair: Japanese→Chinese keeps the tested wording, others name their languages. */
        fun mistranslationQuestion(source: Lang?, target: Target): Map<String, Any> {
            if (source == Lang.JA && target.lang == Lang.ZH) return MISTRANSLATION
            val src = source?.en ?: "Japanese or English"
            return mapOf(
                "type" to "choice",
                "instructions" to "`ja` is $src VTuber livestream speech (raw speech recognition, may contain misheard words); `zh` is a ${target.en} machine translation of it that may be wrong. What is the main problem with `zh`?",
                "criteria" to mapOf(
                    "negation" to "zh adds a negation or refusal that ja does not contain",
                    "reversal" to "zh reverses the meaning or the positive/negative polarity of ja",
                    "literal_metaphor" to "zh translates an idiom or livestream expression literally",
                    "platform_term" to "zh mistranslates a livestream/platform term such as a platform name, superchat, member, gift",
                    "untranslated" to "zh leaves $src words untranslated",
                    "misheard" to "ja itself contains misheard nonsense words and zh translates them literally",
                    "hallucination" to "zh adds content that ja does not say",
                    "ok" to "zh is an acceptable translation",
                ),
            )
        }
    }
}

/**
 * One POST as the session log records it. [error] is a fixed code or an exception class name (a TLS one with its
 * reason), never a message that could hold a header or the body.
 * [retryAfter]: the Retry-After header of a 429, as sent.
 */
class HttpResult(val status: Int, val body: String?, val ms: Long, val error: String?, val firstContentMs: Long? = null,
                 val retryAfter: String? = null)

/**
 * POSTs of one session (Jev and online MT). Each request is an OkHttp [Call], registered before it runs:
 * [cancel] marks the session stopped and cancels every registered call, and a call cancelled before it is
 * enqueued never sends anything (OkHttp's cancellation is terminal). Data already sent cannot be taken back.
 * The caller waits at most [deadlineMs] (and returns at once on [cancel]) even where OkHttp cannot interrupt the
 * work, e.g. a blocking DNS lookup. Such leftover work is bounded process-wide: at most [MAX_PENDING] requests
 * (all sessions) may be unfinished in OkHttp; beyond that a request fails at once with "busy" instead of queueing.
 */
class Calls(private val deadlineMs: Long, dns: Dns = Dns.SYSTEM, /** tests: bypass a system proxy */ proxy: java.net.Proxy? = null,
            /** tests: the routes to try (the app's is the system's, see the desktop Main.kt) */ proxySelector: java.net.ProxySelector? = null) {
    // one dispatcher / connection pool for the process; per-session settings are a cheap derived client
    private val client = BASE.newBuilder()
        .callTimeout(deadlineMs, TimeUnit.MILLISECONDS) // the whole call, including a slowly trickling body
        .dns(dns).apply { proxy?.let { proxy(it) }; proxySelector?.let { proxySelector(it) } }
        .build()
    /** what a caller waits on: the end of its call, and (streamed) the first content */
    private class Waiters(val done: CountDownLatch, val started: CountDownLatch)
    /** registered calls and their callers' latches */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Call, Waiters>()
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; inFlight.forEach { (c, w) -> c.cancel(); w.done.countDown(); w.started.countDown() } }

    /**
     * Streamed answers (online MT): [firstContentMs] is set and [isContent] tells, per body line, whether real output
     * has started (for SSE: the first chunk with non-empty text). Without such a line within [firstContentMs] of the
     * start the call is given up; afterwards the whole call still ends [deadlineMs] after the start. Reading stops
     * after a line [isEnd] accepts (the stream's end marker). [HttpResult.firstContentMs] reports when the content
     * started. Without [firstContentMs] (Jev) the body is read whole, as before.
     */
    fun post(url: String, json: String, headers: Map<String, String>, beforeSend: () -> Unit = {},
             firstContentMs: Long? = null, isContent: (String) -> Boolean = { false },
             isEnd: (String) -> Boolean = { false },
             /** a 429's headers arrived (its Retry-After, as sent): called at once, before its body is read */
             on429: (String?) -> Unit = {},
             /** asked right before the request goes out (after any wait in OkHttp's queue): false = not sent, "paused" */
             admit: () -> Boolean = { true }): HttpResult {
        val t0 = monoMs()
        if (cancelled) return HttpResult(0, null, 0, "cancelled")
        // one-shot: OkHttp may try the next route (a proxy, then a direct connection) when connecting or the TLS
        // handshake fails, but never sends the same request twice once it started writing it (billed twice)
        val bytes = json.toByteArray()
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = JSON
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bytes) }
        }
        val req = Request.Builder().url(url).post(body)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.tag(Admit::class.java, Admit(admit)).build()
        val call = client.newCall(req)
        // counted down by the end of the call, by cancel(), and (streamed) by the first content line
        val done = CountDownLatch(1)
        val started = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicLong(-1)
        fun firstOrNull() = first.get().takeIf { it >= 0 }
        val result = AtomicReference<HttpResult?>()
        /** a 429's status and Retry-After, set as soon as its headers arrive */
        val headersOnly = AtomicReference<HttpResult?>()
        inFlight[call] = Waiters(done, started)
        // cancel() either saw the call in the map or set the flag before this check
        if (cancelled) { inFlight.remove(call); return HttpResult(0, null, 0, "cancelled") }
        var admitted = false
        try {
            beforeSend()
            if (cancelled) return HttpResult(0, null, monoMs() - t0, "cancelled")
            // the slot is returned when OkHttp is really done with the call, not when this caller gives up
            if (!PENDING.tryAcquire()) return HttpResult(0, null, monoMs() - t0, "busy")
            admitted = true
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    PENDING.release()
                    result.set(HttpResult(0, null, monoMs() - t0, why(e, t0), firstOrNull())); done.countDown(); started.countDown()
                }
                override fun onResponse(call: Call, response: Response) {
                    var r: HttpResult? = null
                    val ra = if (response.code == 429) response.header("Retry-After") else null
                    // a 429 counts from its headers: a body still trickling at a deadline must not hide it
                    if (response.code == 429) {
                        // installed before it is published: a caller that gives up at its deadline either sees it
                        // installed (at the headers' time) or not at all, never with its own later clock
                        on429(ra)
                        headersOnly.set(HttpResult(429, null, monoMs() - t0, "body unread", retryAfter = ra))
                    }
                    try {
                        val src = response.body?.source()
                        r = when {
                            src == null -> HttpResult(response.code, null, monoMs() - t0, null, retryAfter = ra)
                            firstContentMs != null && response.code == 200 -> readStream(src, t0, first, started, isContent, isEnd)
                            // a real answer is a few KB; anything far larger is not one
                            src.request(MAX_RESPONSE + 1L) -> HttpResult(response.code, null, monoMs() - t0, "response over $MAX_RESPONSE bytes", retryAfter = ra)
                            else -> HttpResult(response.code, src.buffer.readUtf8(), monoMs() - t0, null, retryAfter = ra)
                        }
                    } catch (e: IOException) { r = HttpResult(response.code, null, monoMs() - t0, why(e, t0), firstOrNull(), ra) }
                    finally {
                        if (firstContentMs != null) {
                            // a stream: publish first, then drop the rest, as closing an unfinished chunked body may
                            // first try to drain it
                            result.set(r); done.countDown(); started.countDown()
                            call.cancel(); response.close(); PENDING.release()
                        } else {
                            response.close(); PENDING.release()
                            result.set(r); done.countDown(); started.countDown()
                        }
                    }
                }
            })
            // no output yet [firstContentMs] after the start (queueing, a slow connection, ...): give up; the end of
            // the call (also a failure) or cancel() ends this wait at once. Decided by when the text arrived, not by
            // when this thread happens to wake up.
            if (firstContentMs != null && firstContentMs < deadlineMs) {
                started.await(maxOf(0, t0 + firstContentMs - monoMs()), TimeUnit.MILLISECONDS)
                if (!cancelled) {
                    val finished = done.count == 0L // read before [first]: text recorded after this read is seen too
                    val f = first.get()
                    // finished without text (an HTTP error, a failure, an empty answer): reported as what it is
                    val late = if (f >= 0) f > firstContentMs else !finished
                    if (late) {
                        call.cancel()
                        return headersOnly.get()?.let { HttpResult(429, null, monoMs() - t0, "no content in ${firstContentMs}ms", firstOrNull(), it.retryAfter) }
                            ?: HttpResult(0, null, monoMs() - t0, "no content in ${firstContentMs}ms", firstOrNull())
                    }
                }
            }
            // the deadline is absolute from the start of post(); cancel() wakes this at once
            done.await(maxOf(0, t0 + deadlineMs - monoMs()), TimeUnit.MILLISECONDS)
            return when {
                cancelled -> HttpResult(result.get()?.status ?: 0, null, monoMs() - t0, "cancelled", firstOrNull())
                else -> result.get() ?: run {
                    call.cancel()
                    headersOnly.get()?.let { HttpResult(it.status, null, monoMs() - t0, "deadline ${deadlineMs}ms", firstOrNull(), it.retryAfter) }
                        ?: HttpResult(0, null, monoMs() - t0, "deadline ${deadlineMs}ms", firstOrNull())
                }
            }
        } catch (e: InterruptedException) {
            call.cancel(); Thread.currentThread().interrupt()
            return HttpResult(0, null, monoMs() - t0, "cancelled", firstOrNull())
        } finally {
            inFlight.remove(call)
            if (!admitted) call.cancel()
        }
    }

    private fun why(e: IOException, t0: Long) = when {
        cancelled -> "cancelled"
        e is PausedException -> "paused"
        // callTimeout cancels the call itself, so it is told apart by elapsed time, not isCanceled()
        monoMs() - t0 >= deadlineMs - 50 -> "deadline ${deadlineMs}ms"
        e.message == "Canceled" -> "cancelled"
        // a TLS failure says why (handshake ended, unknown certificate…); its text never holds a header or the body
        e is javax.net.ssl.SSLException -> "${e.javaClass.name}: ${e.message?.take(200)}"
        else -> e.javaClass.name
    }

    /**
     * Reads a streamed body line by line up to [isEnd], never holding more than [MAX_STREAM] bytes (also for one
     * endless line), and records when the first line [isContent] accepts arrived.
     */
    private fun readStream(src: okio.BufferedSource, t0: Long, first: java.util.concurrent.atomic.AtomicLong,
                           started: CountDownLatch, isContent: (String) -> Boolean, isEnd: (String) -> Boolean): HttpResult {
        val out = StringBuilder(); var used = 0L
        while (true) {
            val nl = src.indexOf('\n'.code.toByte(), 0, MAX_STREAM - used + 1)
            if (nl < 0) {
                // no line end within the budget: the rest is over the cap, or the body ended without a newline
                if (src.request(MAX_STREAM - used + 1)) return HttpResult(200, null, monoMs() - t0, "response over $MAX_STREAM bytes", first.get().takeIf { it >= 0 })
                break // ended: a last unterminated line is not an event (and [DONE] never came after it)
            }
            val line = src.readUtf8(nl).removeSuffix("\r"); src.skip(1); used += nl + 1
            out.append(line).append('\n')
            if (first.get() < 0 && isContent(line)) { first.set(monoMs() - t0); started.countDown() }
            if (isEnd(line)) break
        }
        return HttpResult(200, out.toString(), monoMs() - t0, null, first.get().takeIf { it >= 0 })
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_RESPONSE = 64 * 1024
        /** a streamed answer repeats ~200 bytes of JSON per token: 160 tokens are ~40 KB */
        private const val MAX_STREAM = 256 * 1024
        /** Jev asks from two threads and MT from one, so a few requests per session; more means stuck work */
        const val MAX_PENDING = 8
        private val PENDING = java.util.concurrent.Semaphore(MAX_PENDING)
        private val BASE = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false) // never resend a key to another host
            // only a request not yet written is tried again, on the next route: the body is one-shot (see post)
            .retryOnConnectionFailure(true)
            // the last check before a request is written: a network interceptor runs once the connection is up (after
            // OkHttp's queue, DNS, connect and TLS), on the thread that then sends it
            .addNetworkInterceptor { chain ->
                if (chain.request().tag(Admit::class.java)?.ok?.invoke() == false) throw PausedException()
                chain.proceed(chain.request())
            }
            .build()
        private class Admit(val ok: () -> Boolean)
        /** tests */
        fun pendingNow() = MAX_PENDING - PENDING.availablePermits()
    }
}

/**
 * Request bodies, Jev responses and the session log, on org.json (part of Android; tests and the Mac harness use the
 * reference jar). Converts to and from plain Kotlin maps/lists so callers stay library-free; null is kept as JSON null.
 */
object Json {
    fun encode(v: Any?): String = when (v) {
        is Map<*, *> -> toJson(v).toString()
        is Iterable<*> -> toJson(v).toString()
        else -> JSONArray().put(toJson(v)).toString().removePrefix("[").removeSuffix("]")
    }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is String, is Boolean, is Int, is Long -> v
        is Number -> v.toDouble().takeIf { it.isFinite() } ?: JSONObject.NULL
        else -> v.toString()
    }

    /** @throws IOException if [s] is not one JSON value */
    fun parse(s: String): Any? = try {
        val t = JSONTokener(s)
        val v = t.nextValue()
        if (t.more() && t.nextClean() != 0.toChar()) throw IOException("trailing data after JSON")
        fromJson(v)
    } catch (e: JSONException) { throw IOException("bad JSON: ${e.message}") }

    private fun fromJson(v: Any?): Any? = when (v) {
        JSONObject.NULL, null -> null
        is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = fromJson(v.get(k)) } }
        is JSONArray -> (0 until v.length()).map { fromJson(v.get(it)) }
        else -> v
    }
}
