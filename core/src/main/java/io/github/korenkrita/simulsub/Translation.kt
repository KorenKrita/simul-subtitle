package io.github.korenkrita.simulsub

// Translator prompts and the BYOK client. Pure Kotlin so the Mac app and harness (tools/mac-e2e) compile the same code.

/**
 * What the subtitles are about: the background text and the term list given to the translator. Two come with the app
 * ([DEFAULTS], editable and resettable by [id]); the user adds their own. The terms' 译文 are in one language,
 * [termsLang]: the list is given only when the captions are in that language ([termsFor]), for every scene alike.
 */
data class Scene(val id: String, val label: String, val background: String?, val terms: List<Pair<String, String>>,
                 val termsLang: Target = Target.ZH) {
    /** the term list when the captions are in [target], else none (its 译文 would be in another language) */
    fun termsFor(target: Target): List<Pair<String, String>> = if (target == termsLang) terms else emptyList()

    /** 内容类型's line under the picker: what this scene gives the translator when the captions are in [target] */
    fun summary(target: Target): String = when {
        terms.isEmpty() && background == null -> "告诉翻译这是什么内容，并给常见词定好译法：点「编辑」写说明和术语，或「新建」一个自己的场景。"
        terms.isEmpty() -> "这个场景有背景说明，翻译时会一起交给 LLM。"
        target != termsLang -> (if (background != null) "背景说明会交给 LLM；" else "") +
            "这 ${terms.size} 条术语是${termsLang.zh}的，现在字幕翻成${target.zh}，不会用上。"
        else -> (if (background != null) "背景说明和 " else "") + "${terms.size} 条术语会在翻译时一起交给 LLM。"
    }

    /** one of [DEFAULTS] exactly as shipped */
    val shipped: Boolean get() = this in DEFAULTS
    val builtin: Boolean get() = DEFAULTS.any { it.id == id }

    companion object {
        const val MAX_LABEL = 20
        const val MAX_BACKGROUND = 1000
        const val MAX_TERMS = 200
        const val MAX_TERM = 40
        /** between 原文 and 译文 in the saved value and the prompts; [edit] refuses a term containing it */
        const val TERM_SEP = "→"
        val GENERAL = Scene("GENERAL", "通用", null, emptyList())
        val VTUBER = Scene("VTUBER",
            "B站 VTuber 直播",
            "这是日本虚拟主播（VTuber）在B站的直播，主播在和观众闲聊、念弹幕和SC（醒目留言）。原文是语音识别结果，可能有听错的词；口语里常有语气词和重复。",
            listOf(
                "ビリビリ" to "B站", "スパチャ" to "SC", "スーパーチャット" to "SC", "館長" to "舰长", "艦長" to "舰长",
                // no entry for 流れ(る): as a hard rule it turned "流れるの速く" into "（弹幕）刷屏的速度"; the background (念弹幕)
                // gives the sense, and the Jev literal_metaphor note covers a literal "水流"
                "コメント" to "弹幕", "配信" to "直播", "ギフト" to "礼物",
            ),
        )
        val DEFAULTS = listOf(GENERAL, VTUBER)

        /**
         * A scene from the editor: label one line, background as typed (blank = none), terms as the rows of the two
         * boxes (原文, 译文) whose 译文 are in [termsLang]; a row with both boxes empty is ignored. Left: the scene, or
         * right: what is wrong, for the user.
         */
        fun edit(id: String, label: String, background: String, terms: List<Pair<String, String>>, termsLang: Target): Pair<Scene?, String?> {
            val l = label.replace(Regex("\\s+"), " ").trim()
            if (l.isEmpty()) return null to "名字不能空着"
            if (l.length > MAX_LABEL) return null to "名字最多 $MAX_LABEL 个字"
            val b = background.trim()
            if (b.length > MAX_BACKGROUND) return null to "背景说明最多 $MAX_BACKGROUND 个字"
            val list = ArrayList<Pair<String, String>>()
            for ((i, t) in terms.withIndex()) {
                val src = t.first.trim(); val dst = t.second.trim()
                if (src.isEmpty() && dst.isEmpty()) continue
                if (src.isEmpty()) return null to "第 ${i + 1} 条术语没写原文"
                if (dst.isEmpty()) return null to "第 ${i + 1} 条术语没写译文"
                if (src.length > MAX_TERM || dst.length > MAX_TERM) return null to "第 ${i + 1} 条术语太长（每边最多 $MAX_TERM 个字）"
                if ('\n' in src || '\n' in dst) return null to "第 ${i + 1} 条术语不能换行"
                if (TERM_SEP in src || TERM_SEP in dst) return null to "第 ${i + 1} 条术语里不要写「$TERM_SEP」，左右分开填就行"
                list += src to dst
            }
            if (list.size > MAX_TERMS) return null to "术语最多 $MAX_TERMS 条"
            return Scene(id, l, b.ifEmpty { null }, list, termsLang) to null
        }

        /** The 原文 of the editor's rows as typed (also of a row whose 译文 is still empty): what 生成术语 must not add again. */
        fun sources(rows: List<Pair<String, String>>): Set<String> = rows.mapNotNull { it.first.trim().ifEmpty { null } }.toSet()

        /**
         * The scenes saved by the user (the settings value "scenes": built-ins they edited, by id, and their own)
         * merged with [DEFAULTS]: the built-ins first, then the user's in the order added. An entry that is not
         * exactly as [store] writes it is skipped (a built-in then stays as shipped).
         */
        fun all(saved: String?): List<Scene> {
            val mine = runCatching { org.json.JSONArray(saved ?: "[]") }.getOrNull()?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val t = o.opt("terms") as? org.json.JSONArray ?: return@mapNotNull null
                    // each term is saved as "原文→译文"; [edit] keeps the arrow out of terms
                    val rows = (0 until t.length()).map {
                        val p = (t.opt(it) as? String)?.split(TERM_SEP)?.takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                        p[0] to p[1]
                    }
                    if (listOf("id", "label", "background", "termsLang").any { o.opt(it) !is String }) return@mapNotNull null
                    val lang = Target.parse(o.getString("termsLang")) ?: return@mapNotNull null
                    edit(o.getString("id"), o.getString("label"), o.getString("background"), rows, lang).first
                        ?.takeIf { it.id.isNotEmpty() && it.terms.size == rows.size }
                }
            }.orEmpty().distinctBy { it.id }
            return DEFAULTS.map { d -> mine.firstOrNull { it.id == d.id } ?: d } + mine.filter { !it.builtin }
        }

        /** the settings value of [scenes] (only what differs from the shipped built-ins) */
        fun store(scenes: List<Scene>): String = org.json.JSONArray().apply {
            for (s in scenes) if (!s.shipped) put(org.json.JSONObject().put("id", s.id).put("label", s.label)
                .put("background", s.background ?: "").put("termsLang", s.termsLang.stored)
                .put("terms", org.json.JSONArray().apply { s.terms.forEach { put("${it.first}$TERM_SEP${it.second}") } }))
        }.toString()
    }
}

/**
 * The languages, with the names the translation prompt uses and English names for the Jev questions. Spoken: [SOURCES];
 * the captions: any of them ([Target.PRESETS]) or a typed name.
 */
enum class Lang(val code: String, val zh: String, val en: String) {
    ZH("zh", "中文", "Chinese"), EN("en", "英语", "English"), JA("ja", "日语", "Japanese"), KO("ko", "韩语", "Korean");

    companion object {
        /**
         * What ReazonSpeech (ja-en, one model, no language setting) is used for. The choice only tells the translator
         * and Jev the language; null = auto: Japanese or English, the translator works it out.
         */
        val SOURCES: List<Lang?> = listOf(JA, EN, null)
        fun of(code: String?): Lang? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the captions are translated into: a preset [Lang], or a language the user typed in ([lang] null, [name] as
 * typed: it goes into the prompt and the Jev question as it is). Only the Chinese preset gets the Chinese-only parts
 * (the tuned Japanese→Chinese check and its notes, the shorter output cap); a typed "中文" does not. Terms follow
 * [Scene.termsLang], which may be any target.
 */
data class Target(val lang: Lang?, val name: String) {
    val zh: String get() = lang?.zh ?: name
    val en: String get() = lang?.en ?: name
    /** the settings value: a preset's code, or "custom:<name>" */
    val stored: String get() = lang?.code ?: "$CUSTOM$name"

    companion object {
        private const val CUSTOM = "custom:"
        const val MAX_NAME = 40
        val ZH = of(Lang.ZH)
        /** the caption languages the list offers; anything else is typed in (自己填…) */
        val PRESETS: List<Target> = Lang.entries.map(::of)
        fun of(l: Lang) = Target(l, l.zh)
        /** A typed language name: one line, trimmed; null when empty or longer than [MAX_NAME]. */
        fun custom(name: String): Target? =
            name.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME }?.let { Target(null, it) }
        /** A [stored] value; null for anything else (the caller falls back to [ZH]). */
        fun parse(s: String?): Target? =
            if (s != null && s.startsWith(CUSTOM)) custom(s.removePrefix(CUSTOM)) else Lang.of(s)?.let(::of)
    }
}

/**
 * A model may still emit a think block or a stray tag; it is never shown. Gemma 4 marks its thoughts as a channel
 * (`<|channel>thought…<channel|>`), and a first chunk may hold an empty one even with thinking at its minimum.
 */
fun stripThink(s: String): String = s.replace(THINK_BLOCK, "").replace(THINK_TAG, "").trim()
private val THINK_BLOCK = Regex("(?s)<think>.*?</think>|<\\|channel>thought.*?<channel\\|>")
private val THINK_TAG = Regex("</?think>|<\\|channel>thought|<channel\\|>")
/** Reasoning in the text: a think block, or a Gemma thought channel with something in it (an empty one is no thought). */
fun hasThought(s: String): Boolean = s.contains("<think>") ||
    Regex("(?s)<\\|channel>thought(.*?)(<channel\\|>|$)").findAll(s).any { it.groupValues[1].isNotBlank() }
/** Reasoning that never closed: the model ran out of tokens or time while thinking, so the text is no answer. */
fun openThought(s: String): Boolean = s.lastIndexOf("<think>") > s.lastIndexOf("</think>") ||
    s.lastIndexOf("<|channel>thought") > s.lastIndexOf("<channel|>")

/**
 * Output cap of one translation. 160 tokens covered the longest Chinese output measured (segments are up to ~10 s with
 * Jev). Other targets (Thai, Hindi, Arabic…) take more tokens per sentence: 256, not tuned or measured.
 */
fun mtMaxTokens(target: Target) = if (target.lang == Lang.ZH) 160 else 256

/** Translation: no translated text this long after sending fails the caption. */
const val FIRST_CONTENT_MS = 5_000L

/**
 * One translation call. stop: e=end, l=hit max tokens, f=failure.
 * [raw] is the model output exactly as generated; [text] is what a caption shows. For the session log: [request] is
 * exactly what was sent (the JSON body), [response] the raw HTTP body (BYOK keeps none), [http] its status and
 * [error] a fixed code or exception class. Values the service does not report stay null.
 */
class MtResult(
    val raw: String, val stop: Char, val promptTokens: Int?, val outputTokens: Int?,
    /** ms from sending until the first translated text arrived */
    val firstContentMs: Long? = null,
    val request: String? = null, val response: String? = null, val http: Int? = null, val error: String? = null,
    /** the answer carried reasoning (thinking was not off); never shown */
    val thinking: Boolean = false,
    /** prompt tokens the service reports as served from its prefix cache (null: not reported) */
    val cachedTokens: Int? = null,
) {
    val text: String get() = stripThink(raw)

    /** Text usable as a caption: generation ended normally or ran into the token cap (kept, it is mostly complete). */
    val usable get() = (stop == 'e' || stop == 'l') && text.isNotEmpty()
}

/** The OpenAI-style streamed chat reply (server-sent events) the BYOK translator reads. */
object SseStream {
    class Streamed(val content: String, val finish: String?, val promptTokens: Int?, val outputTokens: Int?,
                   /** reasoning text, a <think> block or reasoning tokens were seen: thinking was not off */
                   val thinking: Boolean = false,
                   /** prompt tokens the service says it served from its prefix cache (null: not reported) */
                   val cachedTokens: Int? = null)

    /** The end marker of an OpenAI-style stream ("data: [DONE]", with or without the space). */
    fun isDone(line: String) = line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]"

    /**
     * A streamed call as an [MtResult]: [request] is what was sent, [response] the body for the log (BYOK: with the
     * key removed). A stopped call keeps what was sent; the pipeline checks running before showing anything.
     */
    fun streamResult(r: HttpResult, request: String, response: String?): MtResult {
        if (r.error == "cancelled") return MtResult("", 'f', null, null, firstContentMs = r.firstContentMs, request = request, http = r.status, error = "cancelled")
        val s = if (r.status == 200) r.body?.let { parseStream(it) } else null
        val stop = when {
            r.status != 200 || s == null -> 'f'
            s.finish == "stop" -> 'e'
            s.finish == "length" -> 'l'
            else -> 'f' // missing or unknown: not a translation we can trust; the raw response is kept
        }
        val error = r.error ?: when {
            r.status != 200 -> "HTTP ${r.status}"; s == null -> "bad response"; stop == 'f' -> "finish_reason"; else -> null
        }
        return MtResult(s?.content ?: "", stop, s?.promptTokens, s?.outputTokens, firstContentMs = r.firstContentMs,
            request = request, response = response, http = r.status, error = error,
            thinking = s?.thinking ?: false, cachedTokens = s?.cachedTokens)
    }

    private val REASONING_FIELDS = listOf("reasoning_content", "reasoning")

    /** One SSE line carrying translated text (not a role-only, empty, usage or keep-alive event). */
    fun hasText(line: String): Boolean {
        val data = line.takeIf { it.startsWith("data:") }?.removePrefix("data:")?.trim() ?: return false
        val j = runCatching { Json.parse(data) as? Map<*, *> }.getOrNull() ?: return false
        val choice = (j["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
        return ((choice?.get("delta") as? Map<*, *>)?.get("content") as? String).orEmpty().isNotEmpty()
    }

    /**
     * OpenAI-style server-sent events: "data: {chunk}" lines up to "data: [DONE]"; other lines (comments, event
     * names, blank) are skipped. Null (not trusted) when the stream does not end with [DONE], a chunk is not a
     * JSON object, reports an error, or its choices / delta / content have the wrong type: part of the text may
     * be missing. Role-only and usage-only chunks are fine.
     */
    fun parseStream(body: String): Streamed? {
        val text = StringBuilder(); var finish: String? = null; var done = false; var thinking = false
        var promptTokens: Int? = null; var outputTokens: Int? = null; var cached: Int? = null
        for (line in body.lineSequence()) {
            if (line.startsWith("event:") && line.removePrefix("event:").trim() == "error") return null
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") { done = true; break }
            val j = runCatching { Json.parse(data) as? Map<*, *> }.getOrNull() ?: return null
            if (j["error"] != null) return null
            val choices = j["choices"] as? List<*> ?: return null
            if (choices.isNotEmpty()) {
                val choice = choices[0] as? Map<*, *> ?: return null
                val delta = choice["delta"]
                if (delta != null && delta !is Map<*, *>) return null
                when (val c = (delta as? Map<*, *>)?.get("content")) { null -> {}; is String -> text.append(c); else -> return null }
                when (val f = choice["finish_reason"]) { null -> {}; is String -> finish = f; else -> return null }
                // DeepSeek / vLLM / SGLang: reasoning_content; OpenRouter and others: reasoning
                if (REASONING_FIELDS.any { ((delta as? Map<*, *>)?.get(it) as? String).orEmpty().isNotEmpty() }) thinking = true
            }
            (j["usage"] as? Map<*, *>)?.let { u ->
                promptTokens = (u["prompt_tokens"] as? Number)?.toInt(); outputTokens = (u["completion_tokens"] as? Number)?.toInt()
                // OpenAI-style prompt_tokens_details.cached_tokens, DeepSeek prompt_cache_hit_tokens
                cached = ((u["prompt_tokens_details"] as? Map<*, *>)?.get("cached_tokens") as? Number)?.toInt()
                    ?: (u["prompt_cache_hit_tokens"] as? Number)?.toInt() ?: cached
                val reasoningTokens = ((u["completion_tokens_details"] as? Map<*, *>)?.get("reasoning_tokens") as? Number)?.toInt()
                if ((reasoningTokens ?: 0) > 0) thinking = true
            }
        }
        if (hasThought(text.toString())) thinking = true
        return if (!done) null else Streamed(text.toString(), finish, promptTokens, outputTokens, thinking, cached)
    }
}

/**
 * Notes for the translation errors Jev names reliably (37-sentence test, /tmp/jps/case/mt-compare.md): for the
 * other error classes its attribution was mostly wrong, so those are not acted on.
 */
val FIX_NOTES = mapOf(
    "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入“不”“不用”“没有”等否定；“〜なんて”表示惊讶或感叹，不是否定。",
    "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反（例如“寂しくない”是“不寂寞”）。",
    "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译（例如“流れる”指弹幕/评论滚动得快，不是水流），按语境意译。",
)

/** [FIX_NOTES] without the Japanese→Chinese examples, for every other language pair. */
val FIX_NOTES_GENERAL = mapOf(
    "negation" to "原文没有否定或拒绝的意思，译文不要凭空加入否定。",
    "reversal" to "注意保持原文的肯定/否定和转折关系，不要把意思翻反。",
    "literal_metaphor" to "原文里的比喻或直播用语不要按字面直译，按语境意译。",
)
fun fixNote(choice: String, source: Lang?, target: Target) =
    (if (source == Lang.JA && target.lang == Lang.ZH) FIX_NOTES else FIX_NOTES_GENERAL)[choice]

/**
 * BYOK system message: everything that is the same for every sentence of a session (instructions, languages, scene
 * background, the scene's whole term list), so that every request starts with the same bytes and a service with prefix
 * caching can reuse it. The term list only when the captions are in its language ([Scene.termsFor]).
 */
fun byokSystem(scene: Scene, source: Lang?, target: Target): String = buildString {
    val src = source?.zh ?: "外语"
    append("你是直播字幕翻译。把【当前句】的${src}翻译成自然、口语化的${target.zh}，准确保留原意、肯定否定和人物关系；")
    append("保留人名，不凭空补充内容。字幕要简短，可以省略无意义的口头重复。")
    append("【前文】只用于理解当前句的指代和省略，不要翻译、不要输出。【注意】是这一句的额外要求。")
    append("只输出当前句的译文，不要解释、注释或其他文字。")
    // the shipped background names a Japanese streamer; for another spoken language the nationality goes
    scene.background?.let { append("\n\n背景：" + if (source == Lang.JA || it != Scene.VTUBER.background) it else it.replace("日本虚拟主播", "虚拟主播")) }
    // the terms are in one language: given only when the captions are in it
    val terms = scene.termsFor(target)
    if (terms.isNotEmpty()) append("\n\n术语（原文出现时必须这样译）：" + terms.joinToString("、") { "${it.first}→${it.second}" })
    // A fragment of a few characters (a cut that caught only the end of a word) made the model retell 【前文】 as if it
    // were the sentence: "日" came back as the whole previous line. Measured with a run-to-run control on a 15 min
    // clip: 4-5 of 72 short lines did that without this rule, 0 with it; on the lines that did not, the answers it
    // changed were as good or better (Index-Translate-35B, deepseek-v4.1-flash). Only tested from Japanese into the
    // built-in Chinese, which its examples are written for.
    if (source == Lang.JA && target.lang == Lang.ZH) append("\n\n如果【当前句】只有几个字（例如「あ」「日」「えっ」这类残缺片段），" +
        "不要根据【前文】把它补全成完整句子，也不要复述【前文】的内容；但这几个字本身仍要翻译成自然的中文（例如「犬」→「狗」、" +
        "「行く」→「去」），不要直接照抄原文。")
}

/**
 * 生成术语: the system message asking the user's LLM for up to [max] terms for a scene, as 原文→译文 lines, none of
 * [have] again. [source] null = Japanese or English.
 */
fun termsSystem(label: String, background: String, have: Set<String>, max: Int, source: Lang?, target: Target) = buildString {
    append("你在为实时字幕翻译整理术语表。字幕把${source?.zh ?: "日语或英语"}的语音翻译成${target.zh}。")
    append("根据下面的场景，列出这类内容里常出现、容易译错或有固定译法的词（专有名词、圈内用语、称呼、梗），最多 $max 条。")
    append("每行一条，格式「原文→译文」，原文用${source?.zh ?: "原文语言"}，译文用${target.zh}；不要编号、解释或其他文字。")
    append("\n\n场景：$label")
    if (background.isNotBlank()) append("\n说明：${background.trim()}")
    if (have.isNotEmpty()) append("\n\n已有的术语，不要重复：" + have.joinToString("、"))
}

/** generating at most this many terms in one go (the answer has to fit the token cap of [generateTerms]) */
const val MAX_GENERATE = 100

/**
 * 生成术语 with the user's LLM API: one request (any thread, blocking, up to a minute). [stored] is the thinking switch
 * saved for this URL + model (a variant name, [ByokTranslator.NONE_WORKED], or null = never tested): never tested, it is
 * found first as 测试连接 does and given to [remember] (a variant name or NONE_WORKED; nothing when inconclusive).
 * Left: the new terms (possibly none), right: why it failed, for the user.
 */
fun generateTerms(service: ByokService, stored: String?, remember: (String) -> Unit, label: String,
                  background: String, have: Set<String>, max: Int, source: Lang?, target: Target)
    : Pair<List<Pair<String, String>>?, String?> {
    val n = max.coerceIn(1, MAX_GENERATE)
    var variant = ByokTranslator.variant(stored)
    if (stored == null) {
        val p = ByokTranslator(service, byokSystem(Scene.GENERAL, source, target), null, maxTokens = mtMaxTokens(target))
        val steps = mutableListOf<ByokTranslator.Step>()
        variant = p.probe(steps)
        when {
            variant != null -> remember(variant.name)
            p.exhausted(steps) -> remember(ByokTranslator.NONE_WORKED)
            // nothing conclusive: the first failure says why (wrong key, unreachable, ...)
            else -> steps.lastOrNull()?.result?.let { if (it.stop == 'f') return null to ByokTranslator.why(it) }
        }
    }
    val t = ByokTranslator(service, termsSystem(label, background, have, n, source, target), variant,
        deadlineMs = 60_000, maxTokens = 40 * n + 200, firstContentMs = 30_000)
    val r = t.translate("列出术语。") ?: return null to "没有收到回复"
    if (r.stop == 'f' && r.text.isEmpty()) return null to when {
        r.error?.startsWith("deadline") == true || r.error?.startsWith("no content") == true -> "一分钟内没有生成完，可以少要几条再试"
        r.error == "unfinished thinking" -> "模型一直在思考，没有给出术语"
        else -> ByokTranslator.why(r)
    }
    // cut at the token cap: the lines that came through still count
    return newTerms(r.text, have, n) to null
}
/**
 * The new terms in an LLM answer to [termsSystem]: lines that parse as 原文→译文 (numbering and bullets dropped),
 * none whose 原文 is in [have] (see [Scene.sources]) or already taken, at most [max].
 */
fun newTerms(answer: String, have: Set<String>, max: Int): List<Pair<String, String>> {
    val seen = have.toMutableSet()
    val out = ArrayList<Pair<String, String>>()
    for (line in stripThink(answer).lines()) {
        if (out.size >= max) break
        val l = line.trim().replace(Regex("^([-*•·]|\\d+[.、)）])\\s*"), "").trim('`', ' ')
        val p = l.split(Regex("→|->|="), limit = 2).map { it.trim() }
        if (p.size < 2 || p[0].isEmpty() || p[1].isEmpty() || p[0].length > Scene.MAX_TERM || p[1].length > Scene.MAX_TERM) continue
        if (Scene.TERM_SEP in p[1]) continue // "a→b→c" is not one term
        if (seen.add(p[0])) out += p[0] to p[1]
    }
    return out
}

/**
 * The editor's rows after 生成术语: [rows] as they are now (typed meanwhile too) with the [generated] terms whose 原文 is
 * not among them added at the end, as many as still fit [Scene.MAX_TERMS]. No row already there is changed or removed.
 * Left: the new rows, right: how many were added.
 */
fun appendTerms(rows: List<Pair<String, String>>, generated: List<Pair<String, String>>): Pair<List<Pair<String, String>>, Int> {
    val have = Scene.sources(rows).toMutableSet()
    val room = Scene.MAX_TERMS - rows.count { it.first.isNotBlank() || it.second.isNotBlank() }
    val add = generated.filter { have.add(it.first) }.take(room.coerceAtLeast(0))
    return rows + add to add.size
}

/** BYOK user message: the parts that change, oldest first, the current sentence and its one-off notes last. */
fun byokUser(text: String, notes: List<String> = emptyList(), previous: List<String> = emptyList()): String = buildString {
    if (previous.isNotEmpty()) append("【前文】\n" + previous.joinToString("\n") + "\n\n")
    append("【当前句】\n$text")
    notes.forEach { append("\n\n【注意】$it") }
}

/**
 * The user's own OpenAI-compatible service (BYOK), the only translator: streamed, so that a slow or queued request is
 * noticed early. No translated text within [firstContentMs] fails the caption, the call ends [deadlineMs] after
 * sending, the caption shows the whole sentence at once, no retry and no fallback: a failure is a failed caption,
 * visible as such. [cancel] is terminal (stop). [system] is fixed for the session; [translate] takes the [byokUser] text.
 *
 * Thinking must be off, but there is no standard switch: [THINKING_OFF] lists the ones we know. [variant] is the one
 * [probe] found for this URL + model (null: none worked, the request carries no switch and may think and be slow).
 * Reasoning in an answer is never shown; it is only flagged ([MtResult.thinking]).
 */
/**
 * One saved LLM API setting (设置 → LLM API → 配置), by name. Its address, model, key, User-Agent and headers are kept by
 * each app under the BYOK storage names plus [suffix]. The first one ("" id) is the setting from before there were
 * several, under the old names: an update finds it as 「默认」, nothing moved.
 */
data class LlmProfile(val id: String, val name: String) {
    val suffix: String get() = if (id.isEmpty()) "" else ":$id"

    companion object {
        const val MAX_NAME = 20
        const val MAX_PROFILES = 20
        val FIRST = LlmProfile("", "默认")

        /** the saved list; never empty: the first one when nothing (or nothing readable) was saved */
        fun all(saved: String?): List<LlmProfile> {
            val a = runCatching { org.json.JSONArray(saved ?: "[]") }.getOrNull() ?: return listOf(FIRST)
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); val name = o.optString("name").trim().take(MAX_NAME)
                // the id ends up in storage names (and a keychain account): only what [newId] makes
                if (name.isEmpty() || !id.all { it in 'a'..'z' || it in '0'..'9' }) null else LlmProfile(id, name)
            }.distinctBy { it.id }.ifEmpty { listOf(FIRST) }
        }

        /** the profile in use: the one saved as active ([id]), else the first */
        fun active(list: List<LlmProfile>, id: String?): LlmProfile = list.firstOrNull { it.id == id } ?: list.first()

        fun store(list: List<LlmProfile>): String = org.json.JSONArray().apply {
            for (p in list) put(org.json.JSONObject().put("id", p.id).put("name", p.name))
        }.toString()

        /** a typed name, trimmed; or why it cannot be one (empty, or another profile's, [self] aside) */
        fun name(typed: String, list: List<LlmProfile>, self: String? = null): Pair<String?, String?> {
            val n = typed.trim()
            return when {
                n.isEmpty() -> null to "名字不能为空"
                n.length > MAX_NAME -> null to "名字最多 $MAX_NAME 个字"
                list.any { it.id != self && it.name.equals(n, ignoreCase = true) } -> null to "已经有叫「$n」的配置了"
                else -> n to null
            }
        }

        /** an id none of [list] has */
        fun newId(list: List<LlmProfile>, now: Long = System.currentTimeMillis()): String {
            var n = now
            while (list.any { it.id == "p$n" }) n++
            return "p$n"
        }
    }
}

/**
 * The user's LLM API as saved: chat URL (see [ByokTranslator.chatUrl]), model, key ("" = none: no Authorization header,
 * for a free service), the User-Agent ("" = [ByokTranslator.defaultUserAgent]) and extra request headers. A data class:
 * a session compares the one it uses with the one saved last.
 */
data class ByokService(val url: String, val model: String, val key: String, val userAgent: String = "",
                       val headers: List<Pair<String, String>> = emptyList()) {
    /** What goes out with each request besides the body. */
    fun requestHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent.ifEmpty { ByokTranslator.defaultUserAgent })
        for ((k, v) in headers) put(k, v)
        if (key.isNotEmpty()) put("Authorization", "Bearer $key")
    }
    /** for the session log: the User-Agent and the names of the extra headers, never their values (they may be keys) */
    /** never the key or the header values */
    override fun toString() = "ByokService($url, $model, key=${if (key.isEmpty()) "none" else "…"}, ua=$userAgent, headers=${headers.map { it.first }})"
    fun log(): Map<String, Any?> = mapOf("mt_user_agent" to (userAgent.ifEmpty { null }), "mt_headers" to headers.map { it.first }.ifEmpty { null })

    companion object {
        const val MAX_HEADERS = 20
        /** set by OkHttp or by the app: the key, the body, the connection */
        private val RESERVED = setOf("authorization", "content-type", "content-length", "content-encoding", "host",
            "connection", "transfer-encoding", "accept-encoding", "user-agent", "cookie", "proxy-authorization", "te",
            "upgrade", "keep-alive", "expect")
        private fun token(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "!#$%&'*+-.^_`|~"

        /** null when [name] may be sent as an extra header, otherwise why not (in words for the user) */
        fun badName(name: String): String? = when {
            name.isEmpty() -> "请求头名字不能空着"
            !name.all(::token) -> "请求头名字「$name」只能用英文字母、数字和 - _ 等符号，不能有空格或冒号"
            name.lowercase() in RESERVED -> if (name.equals("user-agent", true)) "User-Agent 请填在上面的框里"
                else if (name.equals("authorization", true)) "Authorization 由 API Key 自动生成，不能在这里填" else "「$name」由 App 自己设置，不能改"
            else -> null
        }
        /** null when [value] may be sent; printable ASCII and spaces (a header line cannot hold a line break) */
        fun badValue(name: String, value: String): String? =
            if (value.all { it == ' ' || it == '\t' || it in '!'..'~' }) null else "「$name」的值里有换行或中文等字符，请求头里放不下"
        fun badUserAgent(ua: String): String? =
            if (ua.all { it == ' ' || it in '!'..'~' }) null else "User-Agent 里只能有英文字母、数字、空格和常见符号"
        /** null when the typed User-Agent and headers (blank rows already dropped) may be saved, otherwise why not */
        fun badAdvanced(ua: String, headers: List<Pair<String, String>>): String? = badUserAgent(ua)
            ?: headers.firstNotNullOfOrNull { (k, v) -> badName(k) ?: badValue(k, v) }
            ?: headers.groupBy { it.first.lowercase() }.values.firstOrNull { it.size > 1 }?.let { "「${it[0].first}」填了两次" }
            ?: if (headers.size > MAX_HEADERS) "请求头最多 $MAX_HEADERS 个" else null

        /** The extra headers kept as one string (stored encrypted, like the key): a JSON list of [name, value]. */
        fun storeHeaders(h: List<Pair<String, String>>): String = Json.encode(h.map { listOf(it.first, it.second) })
        fun parseHeaders(s: String?): List<Pair<String, String>> = runCatching {
            (Json.parse(s ?: return emptyList()) as List<*>).mapNotNull { e ->
                val l = e as? List<*> ?: return@mapNotNull null
                val k = l.getOrNull(0) as? String ?: return@mapNotNull null
                val v = l.getOrNull(1) as? String ?: return@mapNotNull null
                if (badName(k) == null && badValue(k, v) == null) k to v else null
            }.take(MAX_HEADERS)
        }.getOrDefault(emptyList())
    }
}

class ByokTranslator(
    private val service: ByokService,
    private val system: String,
    private val variant: ThinkingOff?,
    private val deadlineMs: Long = 8_000,
    private val maxTokens: Int = mtMaxTokens(Target.ZH),
    private val firstContentMs: Long = FIRST_CONTENT_MS,
) : Mt {
    private val calls = Calls(deadlineMs)
    private val url = service.url
    private val model = service.model
    /** masked out of every reply: the key and the extra header values (a header may carry a key too) */
    private val secrets = (listOf(service.key) + service.headers.map { it.second }).filter { it.isNotEmpty() }.distinct()

    override fun translate(prompt: String): MtResult? = call(prompt, variant, firstContentMs)

    private fun call(user: String, v: ThinkingOff?, firstMs: Long): MtResult {
        val body = Json.encode(mapOf(
            "model" to model,
            "messages" to listOf(mapOf("role" to "system", "content" to system + (v?.systemNote?.let { "\n\n$it" } ?: "")),
                mapOf("role" to "user", "content" to user)),
            "temperature" to 0, "max_tokens" to maxTokens, "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
        ) + (v?.params ?: emptyMap()))
        val seen = StringBuilder() // this request's content so far, for the first-text check (one per call: a late reader
        // of an earlier, abandoned call must not touch the next one)
        // the URL was checked by chatUrl; anything OkHttp still refuses is a failed call, never a crash
        val r = try {
            calls.post(url, body, service.requestHeaders(),
                firstContentMs = firstMs, isContent = { hasAnswer(it, seen) }, isEnd = SseStream::isDone)
        } catch (e: IllegalArgumentException) { HttpResult(0, null, 0, "bad url") }
        // The key is in a header, not the body, but a service may echo it anywhere (split over chunks, inside
        // reasoning, in an error), where no text replacement on the body can find it. So no BYOK reply body is kept:
        // the log gets the cleaned text, status, timings and usage.
        val res = SseStream.streamResult(r, body, null)
        // reasoning that never closed (the model ran out of tokens while thinking) is not a translation
        val open = openThought(res.raw)
        // what a caption may show: reasoning removed until nothing changes, then the key masked until it is gone, so
        // that MtResult.text (which strips once more) can no longer join anything into the key
        var clean = if (open) "" else res.raw
        while (true) {
            // the mask has no key characters, so each pass strictly shrinks or ends (a key like "*" could grow it)
            val next = secrets.fold(stripThink(clean)) { t, k -> t.replace(k, "") }
            if (next == clean) break
            clean = next
        }
        // an answer cut at the token cap inside reasoning is a conclusive "it thinks" for the probe, but no caption
        return MtResult(clean, if (open) 'f' else res.stop, res.promptTokens,
            res.outputTokens, firstContentMs = res.firstContentMs, request = res.request, response = null, http = res.http,
            error = if (open) (if (res.stop == 'l' || res.stop == 'e') "unfinished thinking" else res.error ?: "unfinished thinking") else res.error,
            thinking = res.thinking || open || hasThought(res.raw), cachedTokens = res.cachedTokens)
    }

    /**
     * Translated text has started (for the 1 s cutoff): [seen] collects this request's content; outside complete
     * <think>…</think> blocks, an unclosed <think> and a trailing part of a tag (a tag split over chunks) there is
     * something besides whitespace.
     */
    private fun hasAnswer(line: String, seen: StringBuilder): Boolean {
        if (!SseStream.hasText(line)) return false
        val j = runCatching { Json.parse(line.removePrefix("data:").trim()) as? Map<*, *> }.getOrNull() ?: return false
        seen.append((((j["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("delta") as? Map<*, *>)?.get("content") as? String ?: return false)
        var s = seen.toString().replace(Regex("(?s)<think>.*?</think>|<\\|channel>thought.*?<channel\\|>"), "")
            .replace(Regex("(?s)(<think>|<\\|channel>thought).*"), "")
        // "<", "<thi", "<|chan", "</thin" … at the end may be the start of a tag still arriving
        val lt = s.lastIndexOf('<')
        if (lt >= 0 && TAGS.any { it.startsWith(s.substring(lt)) }) s = s.substring(0, lt)
        return s.replace("</think>", "").replace("<channel|>", "").isNotBlank()
    }

    override fun cancel() = calls.cancel()

    /**
     * One way to ask for no thinking; [params] are added to the request body and [systemNote] (when set) to the end of
     * the system message, the same for every sentence of a session, so the prefix stays cacheable.
     */
    class ThinkingOff(val name: String, val label: String, val params: Map<String, Any>, val systemNote: String? = null)

    /** One probe step: which variant, what came back. */
    class Step(val variant: ThinkingOff, val result: MtResult)

    /**
     * Tries [THINKING_OFF] in order with a short test sentence until one ends normally with an answer and without
     * reasoning; that one is returned (the caller keeps it for this URL + model). Null = none did, see [steps]: when
     * every switch was tried (steps.size == THINKING_OFF.size, nothing cancelled) none works for this model; otherwise
     * the probe stopped early and should run again later. Waits up to the whole deadline for each (no 1 s first-text
     * cutoff: a slow first answer is not a wrong switch). Stops early when the service fails for a reason no switch
     * changes (wrong key, unknown model, rate limit, not reachable).
     */
    fun probe(steps: MutableList<Step>): ThinkingOff? {
        for (v in THINKING_OFF) {
            val r = call(byokUser(PROBE_TEXT), v, deadlineMs)
            steps += Step(v, r)
            if (r.error == "cancelled") return null
            if (r.stop == 'e' && r.text.isNotEmpty() && !r.thinking) return v
            if (fatal(r)) return null
        }
        return null
    }

    /**
     * After [probe] returned null: true when every switch got a conclusive answer (a full answer that still thought, or
     * the parameter refused with 400/422) so none turns thinking off; false when any step was inconclusive
     * (cancelled, timeout, 5xx, broken reply, a service error): then nothing is stored and the next use probes again.
     */
    fun exhausted(steps: List<Step>) = steps.size == THINKING_OFF.size &&
        // conclusive: a normally ended (or token-capped) answer, or the parameter refused; a thinking answer without a
        // proper end (no finish_reason, cut off) proves nothing
        steps.all { ended(it.result) || it.result.http == 400 || it.result.http == 422 } && steps.any { ended(it.result) }
    /** The reply ended properly (stop or token cap), also when it was cut inside reasoning and so shows nothing. */
    // an empty answer that did not think (e.g. text in a field we do not read) is not a conclusive "it thinks"
    private fun ended(r: MtResult) = ((r.stop == 'e' || r.stop == 'l') && (r.thinking || r.text.isNotEmpty())) ||
        r.error == "unfinished thinking"
    // wrong key / no access / unknown model or path / rate limited / not reachable: no switch changes that
    // (a timeout is not among them: thinking can make an answer slow)
    private fun fatal(r: MtResult) = r.http in listOf(401, 403, 404, 429) || (r.http == 0 && r.error?.startsWith("deadline") != true)

    companion object {
        const val PROBE_TEXT = "こんにちは、今日もよろしくお願いします。"
        /** think tags, for a tag split over stream chunks */
        private val TAGS = listOf("<think>", "</think>", "<|channel>thought", "<channel|>")
        /** Stored instead of a variant when the service answered but every switch still thought: do not probe again. */
        const val NONE_WORKED = "-"
        /** Sent when the user set none: "SimulSub/<version>", set by each app at start. */
        @Volatile var defaultUserAgent = "SimulSub"

        /** A failed call in words for the user (no response text: it may hold anything). */
        fun why(r: MtResult): String = when {
            r.http == 401 || r.http == 403 -> "key 不对、没填或没有权限（HTTP ${r.http}）"
            r.http == 404 -> "地址或模型 ID 不对（HTTP 404）"
            r.http == 429 -> "请求太频繁或额度用完（HTTP 429）"
            r.http == 412 -> "被服务的防火墙拦下了（HTTP 412），可能要在「高级设置」里填它要求的 User-Agent"
            r.http != null && r.http != 0 && r.http != 200 -> "服务返回错误（HTTP ${r.http}）"
            r.error?.startsWith("deadline") == true -> "8 秒内没有翻完"
            r.error?.startsWith("no content") == true -> "${FIRST_CONTENT_MS / 1000} 秒内没有收到译文"
            r.error == "bad response" || r.error == "finish_reason" -> "回复的格式不对，可能不是 OpenAI 兼容的流式接口"
            r.error == null -> "没有收到译文"
            r.error!!.contains("UnknownHost") -> "找不到这个域名"
            r.error!!.contains("SSL") || r.error!!.contains("Certificate") -> "安全连接失败（证书问题）"
            else -> "连不上（${r.error!!.substringAfterLast('.')}）"
        }

        /**
         * Known ways to turn thinking off, most specific first. A service that does not know a field usually ignores
         * it, but some reject unknown fields (HTTP 400); then the next one is tried.
         */
        val THINKING_OFF = listOf(
            // DeepSeek API and services copying it (also GLM / Kimi style "thinking")
            ThinkingOff("thinking", "thinking: disabled", mapOf("thinking" to mapOf("type" to "disabled"))),
            // vLLM / SGLang / many Qwen3 hosts: the chat template switch
            ThinkingOff("template", "chat_template_kwargs", mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false, "thinking" to false))),
            // SiliconFlow, DashScope (Qwen) and others: a top-level flag
            ThinkingOff("enable", "enable_thinking: false", mapOf("enable_thinking" to false)),
            // OpenRouter: the unified reasoning object
            ThinkingOff("reasoning", "reasoning: enabled false", mapOf("reasoning" to mapOf("enabled" to false))),
            // OpenAI-style reasoning models and Gemini's OpenAI endpoint
            ThinkingOff("effort", "reasoning_effort: none", mapOf("reasoning_effort" to "none")),
            // Gemma 4 (e.g. on Gemini's OpenAI endpoint): no "none", only minimal or high, and minimal still lets it think
            // now and then, so the system message asks it not to as well
            ThinkingOff("minimal", "reasoning_effort: minimal + 提示词", mapOf("reasoning_effort" to "minimal"),
                "CRITICAL: Do not use <|think|>, do not output any reasoning steps, and do not think. " +
                    "Directly output the translation result only.\n直接输出翻译结果，禁止输出任何思考过程与推理说明。"),
            // the model may not think at all: nothing extra
            ThinkingOff("none", "不加参数", emptyMap()),
        )
        fun variant(name: String?) = THINKING_OFF.firstOrNull { it.name == name }

        /**
         * The chat completions URL for what the user typed: http or https (any other scheme is refused), no user info,
         * query or fragment; a path ending in /v1 (or /v1/chat/completions) is used as is, otherwise /v1 is added. The
         * scheme the user typed is kept, so a local or LAN endpoint over plain http works, but a key sent over http
         * travels in clear text. Returns (error text, null) or (null, url).
         */
        fun chatUrl(input: String): Pair<String?, String?> {
            val s = input.trim()
            if (s.isEmpty()) return "还没填地址" to null
            val u = runCatching { java.net.URI(s) }.getOrNull() ?: return "地址格式不对" to null
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return "地址要以 http:// 或 https:// 开头" to null
            if (u.host.isNullOrEmpty()) return "地址里没有域名" to null
            if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return "地址里不要带用户名、?参数 或 #" to null
            if (u.port == 0 || u.port > 65535) return "端口号不对" to null
            var path = (u.rawPath ?: "").trimEnd('/')
            path = path.removeSuffix("/chat/completions")
            if (!path.endsWith("/v1")) path += "/v1"
            val port = if (u.port > 0) ":${u.port}" else ""
            val out = "$scheme://${u.host.lowercase()}$port$path/chat/completions"
            // what OkHttp will be given: it must accept it too (IPv6 brackets, odd characters, ...)
            if (okhttp3.HttpUrl.Companion.run { out.toHttpUrlOrNull() } == null) return "地址格式不对" to null
            return null to out
        }
    }
}
