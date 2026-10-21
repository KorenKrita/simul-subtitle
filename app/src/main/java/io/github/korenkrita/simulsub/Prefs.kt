package io.github.korenkrita.simulsub

import android.content.Context

/** Subtitle look, shared by the settings screen (live preview) and the overlay. */
class Prefs(ctx: Context) : SessionPrefs {
    private val sp = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)
    // every LLM API request (the session, 测试连接, 生成术语) is made after some Prefs exists
    init { ByokTranslator.defaultUserAgent = "SimulSub/${BuildConfig.VERSION_NAME}" }

    /** Chinese caption size in sp */
    var textSp: Float
        get() = sp.getFloat("textSp", 13f)
        set(v) = sp.edit().putFloat("textSp", v).apply()

    /** Background opacity 0..1 */
    var bgAlpha: Float
        get() = sp.getFloat("bgAlpha", CaptionOverlayView.DEFAULT_ALPHA)
        set(v) = sp.edit().putFloat("bgAlpha", v).apply()

    /** Size and opacity in one edit, so a change listener never sees one new and one old value. */
    fun setStyle(sizeSp: Float, alpha: Float) = sp.edit().putFloat("textSp", sizeSp).putFloat("bgAlpha", alpha).apply()

    /** Home screen theme: "system", "light" or "dark" */
    var theme: String
        get() = sp.getString("theme", "system")!!
        set(v) = sp.edit().putString("theme", v).apply()

    /** Caption colour scheme id, see CaptionOverlayView.PALETTES */
    var palette: String
        get() = sp.getString("palette", CaptionOverlayView.PALETTES[0].id)!!
        set(v) = sp.edit().putString("palette", v).apply()

    /** Overlay width as a fraction of the screen width */
    var widthFrac: Float
        get() = sp.getFloat("widthFrac", DEFAULT_WIDTH)
        set(v) = sp.edit().putFloat("widthFrac", v).apply()

    /** Caption area height as a fraction of the usable screen height; 0 = fit the text (default) */
    var heightFrac: Float
        get() = sp.getFloat("heightFrac", 0f)
        set(v) = sp.edit().putFloat("heightFrac", v).apply()

    /** Show the recognised Japanese above the Chinese line */
    override var showJa: Boolean
        get() = sp.getBoolean("showJa", true)
        set(v) = sp.edit().putBoolean("showJa", v).apply()

    /** Show the small timing line under the captions */
    override var showStats: Boolean
        get() = sp.getBoolean("showStats", true)
        set(v) = sp.edit().putBoolean("showStats", v).apply()

    /** Overlay position (top-left, px); -1 = default */
    var overlayX: Int
        get() = sp.getInt("overlayX", -1)
        set(v) = sp.edit().putInt("overlayX", v).apply()
    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit().putInt("overlayY", v).apply()
    /** The card's bottom edge when it was placed (px, same space as [overlayY]); -1 = not saved */
    var overlayBottom: Int
        get() = sp.getInt("overlayBottom", -1)
        set(v) = sp.edit().putInt("overlayBottom", v).apply()

    /** Overlay keeps old sentences until there are more than [keepSentences] AND more than [keepChars] characters. */
    override var keepSentences: Int
        get() = sp.getInt("keepSentences", CaptionBoard.DEFAULT_SENTENCES)
        set(v) = sp.edit().putInt("keepSentences", v).apply()
    override var keepChars: Int
        get() = sp.getInt("keepChars", CaptionBoard.DEFAULT_CHARS)
        set(v) = sp.edit().putInt("keepChars", v).apply()

    /** true = only show finished sentences; false = live drafts that are corrected in place. Overlay and home share it. */
    override var finalOnly: Boolean
        get() = sp.getBoolean("finalOnly", false)
        set(v) = sp.edit().putBoolean("finalOnly", v).apply()

    /** Spoken language (null = auto: Japanese or English) and caption language; read when a session starts. */
    override var sourceLang: Lang?
        get() = sp.getString("sourceLang", "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = sp.edit().putString("sourceLang", v?.code ?: "auto").apply()
    /** a preset language, or one the user typed ("custom:<name>") */
    override var targetLang: Target
        get() = Target.parse(sp.getString("targetLang", null)) ?: Target.ZH
        set(v) = sp.edit().putString("targetLang", v.stored).apply()

    /** The built-in scenes (as edited) and the user's own, see [Scene.all]. */
    var scenes: List<Scene>
        get() = Scene.all(sp.getString("scenes", null))
        set(v) = sp.edit().putString("scenes", Scene.store(v)).apply()
    /** What the subtitles are about (translator background + term list), one of [scenes]; read when a session starts. */
    override var scene: Scene
        get() = sp.getString("scene", null).let { id -> scenes.let { all -> all.firstOrNull { it.id == id } ?: all.first() } }
        set(v) = sp.edit().putString("scene", v.id).apply()

    /** Use Jev (cloud) for sentence ends; needs the key of [jevProvider]. */
    override var jevOn: Boolean
        get() = sp.getBoolean("jevOn", false)
        set(v) = sp.edit().putBoolean("jevOn", v).apply()
    /** Check every final translation with Jev; independent of [jevOn]. Off by default. */
    override var jevCheck: Boolean
        get() = sp.getBoolean("jevCheck", false)
        set(v) = sp.edit().putBoolean("jevCheck", v).apply()
    /** Drop a repeat at the join of a kept draft and the rest (Pipeline.dedupeJoin). Off by default. */
    override var dedupeJoin: Boolean
        get() = sp.getBoolean("dedupeJoin", false)
        set(v) = sp.edit().putBoolean("dedupeJoin", v).apply()
    /** Write a session log (Settings → 使用记录). Off by default: nothing is recorded until the user turns it on. */
    override var keepLogs: Boolean
        get() = sp.getBoolean("keepLogs", false)
        set(v) = sp.edit().putBoolean("keepLogs", v).apply()
    override var keepTranscript: Boolean
        get() = sp.getBoolean("keepTranscript", true)
        set(v) = sp.edit().putBoolean("keepTranscript", v).apply()
    /** Give the translator the last finished sentences as context (on by default). */
    override var contextOn: Boolean
        get() = sp.getBoolean("contextOn", true)
        set(v) = sp.edit().putBoolean("contextOn", v).apply()
    override var contextSentences: Int
        get() = sp.getInt("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = sp.edit().putInt("contextSentences", v).apply()
    override var jevProvider: JevProvider
        // with no choice yet, the free Zen endpoint
        get() = runCatching { JevProvider.valueOf(sp.getString("jevProvider", null) ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = sp.edit().putString("jevProvider", v.name).apply()

    /** API key per provider, encrypted with an Android Keystore key (the stored value is useless off this device). */
    override fun jevKey(p: JevProvider): String? = sp.getString("jevKey:${p.name}", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    fun setJevKey(p: JevProvider, key: String?) =
        sp.edit().apply { if (key.isNullOrBlank()) remove("jevKey:${p.name}") else putString("jevKey:${p.name}", KeyBox.seal(key.trim())) }.apply()

    /**
     * BYOK: the user's OpenAI-compatible services, saved as [LlmProfile]s; the one in use is byokActive. Each one's
     * fields are the storage names plus its suffix (the first one: the names from before). Keys are sealed like Jev's.
     */
    val byokProfiles: List<LlmProfile> get() = LlmProfile.all(sp.getString("byokProfiles", null))
    val byokProfile: LlmProfile get() = LlmProfile.active(byokProfiles, sp.getString("byokActive", null))
    private val sfx: String get() = byokProfile.suffix
    val byokUrl: String get() = sp.getString("byokUrl$sfx", "") ?: ""
    val byokModel: String get() = sp.getString("byokModel$sfx", "") ?: ""
    val byokKey: String? get() = sp.getString("byokKey$sfx", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
    /**
     * "保存": all fields of the profile in use in one edit (null key = keep the saved one), plus the byokSaved marker a
     * running session switches on. One edit, so a reader never sees the new address with the old key.
     */
    fun saveByok(url: String, model: String, key: String?, userAgent: String? = null,
                 headers: List<Pair<String, String>>? = null) = sp.edit().apply {
        val s = sfx
        putString("byokUrl$s", url.trim()); putString("byokModel$s", model.trim())
        if (key != null) { if (key.isBlank()) remove("byokKey$s") else putString("byokKey$s", KeyBox.seal(key.trim())) }
        if (userAgent != null) { if (userAgent.isBlank()) remove("byokUserAgent$s") else putString("byokUserAgent$s", userAgent.trim()) }
        // sealed like the key: a header may carry a second key
        if (headers != null) { if (headers.isEmpty()) remove("byokHeaders$s") else putString("byokHeaders$s", KeyBox.seal(ByokService.storeHeaders(headers))) }
        putLong("byokSaved", System.nanoTime())
    }.apply()
    private fun ready(s: String) =
        ByokTranslator.chatUrl(sp.getString("byokUrl$s", "") ?: "").second != null && !sp.getString("byokModel$s", "").isNullOrEmpty()
    /** use [p] from now on (a running session: from the next sentence, when [p] is filled in) */
    fun useByokProfile(p: LlmProfile) = sp.edit().apply {
        putString("byokActive", p.id)
        if (ready(p.suffix)) putLong("byokSaved", System.nanoTime())
    }.apply()
    /** a new, empty profile named [name] (checked by [LlmProfile.name]), put in use */
    fun addByokProfile(name: String): LlmProfile {
        val list = byokProfiles
        val p = LlmProfile(LlmProfile.newId(list), name)
        sp.edit().putString("byokProfiles", LlmProfile.store(list + p)).putString("byokActive", p.id).apply()
        return p
    }
    fun renameByokProfile(p: LlmProfile, name: String) =
        sp.edit().putString("byokProfiles", LlmProfile.store(byokProfiles.map { if (it.id == p.id) it.copy(name = name) else it })).apply()
    /** [p] and its fields (never the last one); the one in use: the first left takes over */
    fun deleteByokProfile(p: LlmProfile) {
        val left = byokProfiles.filter { it.id != p.id }.ifEmpty { return }
        val wasActive = byokProfile.id == p.id
        sp.edit().apply {
            for (k in listOf("byokUrl", "byokModel", "byokKey", "byokUserAgent", "byokHeaders")) remove(k + p.suffix)
            putString("byokProfiles", LlmProfile.store(left))
            if (wasActive) {
                putString("byokActive", left.first().id)
                if (ready(left.first().suffix)) putLong("byokSaved", System.nanoTime())
            }
        }.apply()
    }
    val byokUserAgent: String get() = sp.getString("byokUserAgent$sfx", null).orEmpty()
    val byokHeaders: List<Pair<String, String>>
        get() = ByokService.parseHeaders(sp.getString("byokHeaders$sfx", null)?.let { runCatching { KeyBox.open(it) }.getOrNull() })
    /**
     * The saved service read together (one copy of the preferences), for a session building its translator; null when
     * the address is not valid. The key may be "" (a service without one).
     */
    override fun byokService(): ByokService? {
        val all = sp.all
        val s = LlmProfile.active(LlmProfile.all(all["byokProfiles"] as? String), all["byokActive"] as? String).suffix
        val url = ByokTranslator.chatUrl((all["byokUrl$s"] as? String).orEmpty()).second ?: return null
        fun open(k: String) = (all[k] as? String)?.let { runCatching { KeyBox.open(it) }.getOrNull() }
        return ByokService(url, (all["byokModel$s"] as? String).orEmpty(), open("byokKey$s").orEmpty(),
            (all["byokUserAgent$s"] as? String).orEmpty(), ByokService.parseHeaders(open("byokHeaders$s")))
    }
    /**
     * The [ByokTranslator.THINKING_OFF] variant (by name) that answered without reasoning for this exact service URL and
     * model; null = not found yet. Kept per URL + model, so switching back and forth does not probe again.
     */
    override fun byokVariant(url: String, model: String): String? = ByokTranslator.current(sp.getString("byokVariant:$url|$model", null))
    override fun setByokVariant(url: String, model: String, name: String?) =
        sp.edit().apply { if (name == null) remove("byokVariant:$url|$model") else putString("byokVariant:$url|$model", name) }.apply()
    /** URL and model are there and valid: BYOK can start (the key may be left empty, for a free service). */
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty()


    fun listen(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        /** Width slider / resize range as a fraction of the screen width. */
        const val MIN_WIDTH = 0.40f
    }
}

/** AES-GCM with a non-exportable Android Keystore key; output = base64(iv ‖ ciphertext). */
private object KeyBox {
    private const val ALIAS = "jpsub-jev"
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun seal(s: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        return android.util.Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), android.util.Base64.NO_WRAP)
    }
    fun open(b64: String): String {
        val b = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12))
    }
}
