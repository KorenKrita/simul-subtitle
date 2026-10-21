package io.github.korenkrita.simulsub

import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the app keeps its settings and logs: ~/Library/Application Support/SimulSub, %APPDATA%\SimulSub on Windows
 * (or -Dsimulsub.data).
 */
val dataDir: File = File(System.getProperty("simulsub.data")
    ?: if (isWindows) "${System.getenv("APPDATA") ?: System.getProperty("user.home")}\\SimulSub"
    else "${System.getProperty("user.home")}/Library/Application Support/SimulSub").also { it.mkdirs() }

/**
 * The phone's settings with the same names and defaults, kept in settings.json; keys (Jev, BYOK) go to the macOS
 * Keychain (on Windows: keys.json, encrypted for this Windows user) instead. Every read also reads [rev], so a composable that shows a setting follows its changes.
 * Listeners get the changed key on the thread that changed it, like SharedPreferences.
 */
class Prefs internal constructor(private val file: File) : SessionPrefs {
    private val lock = Any()
    private val json: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val rev = mutableIntStateOf(0)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private fun <T> get(read: JSONObject.() -> T): T { rev.intValue; return synchronized(lock) { json.read() } }
    private fun put(vararg kv: Pair<String, Any?>) {
        synchronized(lock) {
            for ((k, v) in kv) if (v == null) json.remove(k) else json.put(k, v)
            // whole-file replace: a crash mid-write never leaves half a settings file
            val tmp = File(file.path + ".tmp")
            // File.renameTo does not replace an existing file on Windows: every save after the first was lost there
            runCatching {
                tmp.writeText(json.toString(1))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { System.err.println("settings: not saved: $it") }
        }
        rev.intValue++
        for ((k, _) in kv) listeners.forEach { it(k) }
    }
    private fun float(k: String, d: Float) = get { optDouble(k, d.toDouble()).toFloat() }
    private fun int(k: String, d: Int) = get { optInt(k, d) }
    private fun bool(k: String, d: Boolean) = get { optBoolean(k, d) }
    private fun str(k: String): String? = get { if (has(k)) optString(k) else null }

    var textSp: Float get() = float("textSp", 13f); set(v) = put("textSp" to v)
    var bgAlpha: Float get() = float("bgAlpha", DEFAULT_ALPHA); set(v) = put("bgAlpha" to v)
    fun setStyle(sizeSp: Float, alpha: Float) = put("textSp" to sizeSp, "bgAlpha" to alpha)
    /** page colours: "system", "light" or "dark" */
    var theme: String get() = str("theme") ?: "system"; set(v) = put("theme" to v)
    var palette: String get() = str("palette") ?: PALETTES[0].id; set(v) = put("palette" to v)
    /** caption card width as a part of the screen width */
    var widthFrac: Float get() = float("widthFrac", DEFAULT_WIDTH); set(v) = put("widthFrac" to v)
    /** caption area height as a part of the screen height; 0 = follows the content */
    var heightFrac: Float get() = float("heightFrac", 0f); set(v) = put("heightFrac" to v)
    override var showJa: Boolean get() = bool("showJa", true); set(v) = put("showJa" to v)
    override var showStats: Boolean get() = bool("showStats", true); set(v) = put("showStats" to v)
    /** the card's top-left in points, on any display (left of or above the main one is negative); null = not placed yet */
    val overlayX: Int? get() = get { if (has("overlayX")) optInt("overlayX") else null }
    val overlayY: Int? get() = get { if (has("overlayY")) optInt("overlayY") else null }
    /** the card's bottom edge when it was placed, in the same points as [overlayY] */
    val overlayBottom: Int? get() = get { if (has("overlayBottom")) optInt("overlayBottom") else null }
    fun setOverlay(x: Int?, y: Int?, bottom: Int?, widthFrac: Float, heightFrac: Float) =
        put("overlayX" to x, "overlayY" to y, "overlayBottom" to bottom, "widthFrac" to widthFrac, "heightFrac" to heightFrac)
    override var keepSentences: Int get() = int("keepSentences", CaptionBoard.DEFAULT_SENTENCES); set(v) = put("keepSentences" to v)
    override var keepChars: Int get() = int("keepChars", CaptionBoard.DEFAULT_CHARS); set(v) = put("keepChars" to v)
    override var finalOnly: Boolean get() = bool("finalOnly", false); set(v) = put("finalOnly" to v)
    /** null: not sure (the recognizer decides) */
    override var sourceLang: Lang?
        get() = (str("sourceLang") ?: "ja").let { if (it == "auto") null else Lang.of(it) ?: Lang.JA }
        set(v) = put("sourceLang" to (v?.code ?: "auto"))
    /** a preset language, or one the user typed ("custom:<name>") */
    override var targetLang: Target get() = Target.parse(str("targetLang")) ?: Target.ZH; set(v) = put("targetLang" to v.stored)
    /** The built-in scenes (as edited) and the user's own, see [Scene.all]. */
    var scenes: List<Scene> get() = Scene.all(str("scenes")); set(v) = put("scenes" to Scene.store(v))
    /** one of [scenes] */
    override var scene: Scene
        get() = str("scene").let { id -> scenes.let { all -> all.firstOrNull { it.id == id } ?: all.first() } }
        set(v) = put("scene" to v.id)
    override var jevOn: Boolean get() = bool("jevOn", false); set(v) = put("jevOn" to v)
    override var jevCheck: Boolean get() = bool("jevCheck", false); set(v) = put("jevCheck" to v)
    override var dedupeJoin: Boolean get() = bool("dedupeJoin", false); set(v) = put("dedupeJoin" to v)
    override var keepLogs: Boolean get() = bool("keepLogs", false); set(v) = put("keepLogs" to v)
    override var keepTranscript: Boolean get() = bool("keepTranscript", true); set(v) = put("keepTranscript" to v)
    override var contextOn: Boolean get() = bool("contextOn", true); set(v) = put("contextOn" to v)
    override var contextSentences: Int
        get() = int("contextSentences", Pipeline.PREVIOUS_SENTENCES).coerceIn(1, Pipeline.MAX_PREVIOUS_SENTENCES)
        set(v) = put("contextSentences" to v)
    override var jevProvider: JevProvider
        get() = runCatching { JevProvider.valueOf(str("jevProvider") ?: "") }.getOrDefault(JevProvider.ZEN)
        set(v) = put("jevProvider" to v.name)
    var autoUpdate: Boolean get() = bool("autoUpdate", true); set(v) = put("autoUpdate" to v)
    /** a release the user chose to skip (its versionCode) */
    var skippedUpdate: Int get() = int("skippedUpdate", 0); set(v) = put("skippedUpdate" to v)


    override fun jevKey(p: JevProvider): String? { rev.intValue; return Keychain.get("jevKey:${p.name}") }
    /** false when the keychain refused it */
    fun setJevKey(p: JevProvider, key: String?): Boolean =
        Keychain.set("jevKey:${p.name}", key?.trim()).also { if (it) put("jevKey:${p.name}" to null) }

    /**
     * BYOK: the user's OpenAI-compatible services, saved as [LlmProfile]s; the one in use is byokActive. Each one's
     * fields are the storage names plus its suffix (the first one: the names from before). Keys in the keychain.
     */
    val byokProfiles: List<LlmProfile> get() = LlmProfile.all(str("byokProfiles"))
    val byokProfile: LlmProfile get() = LlmProfile.active(byokProfiles, str("byokActive"))
    private val sfx: String get() = byokProfile.suffix
    val byokUrl: String get() = str("byokUrl$sfx").orEmpty()
    val byokModel: String get() = str("byokModel$sfx").orEmpty()
    val byokKey: String? get() { rev.intValue; return Keychain.get("byokKey$sfx") }
    /** BYOK's fields and key change together: a running session must never pair a new key with an old address */
    private val byokLock = Any()
    /**
     * "保存": the fields, then byokSaved, so a running session never takes a half-saved set. False when the keychain
     * refused the key or the headers; then nothing else is saved and the session keeps the old set. [key] null keeps
     * the saved one; [headers] null keeps the saved ones (stored with the key: they may hold one).
     */
    fun saveByok(url: String, model: String, key: String?, userAgent: String? = null,
                 headers: List<Pair<String, String>>? = null): Boolean = synchronized(byokLock) {
        val s = sfx
        if (key != null && !Keychain.set("byokKey$s", key.trim().ifEmpty { null })) return false
        if (headers != null && !Keychain.set("byokHeaders$s", if (headers.isEmpty()) null else ByokService.storeHeaders(headers))) return false
        put("byokUrl$s" to url.trim(), "byokModel$s" to model.trim())
        if (userAgent != null) put("byokUserAgent$s" to userAgent.trim().ifEmpty { null })
        put("byokSaved" to System.nanoTime())
        true
    }
    private fun ready(s: String) =
        ByokTranslator.chatUrl(str("byokUrl$s") ?: "").second != null && !str("byokModel$s").isNullOrEmpty()
    /** use [p] from now on (a running session: from the next sentence, when [p] is filled in) */
    fun useByokProfile(p: LlmProfile) = synchronized(byokLock) {
        if (ready(p.suffix)) put("byokActive" to p.id, "byokSaved" to System.nanoTime()) else put("byokActive" to p.id)
    }
    /** a new, empty profile named [name] (checked by [LlmProfile.name]), put in use */
    fun addByokProfile(name: String): LlmProfile = synchronized(byokLock) {
        val list = byokProfiles
        val p = LlmProfile(LlmProfile.newId(list), name)
        put("byokProfiles" to LlmProfile.store(list + p), "byokActive" to p.id)
        p
    }
    fun renameByokProfile(p: LlmProfile, name: String) = synchronized(byokLock) {
        put("byokProfiles" to LlmProfile.store(byokProfiles.map { if (it.id == p.id) it.copy(name = name) else it }))
    }
    /** [p] and its fields (never the last one); the one in use: the first left takes over. False when the keychain refused. */
    fun deleteByokProfile(p: LlmProfile): Boolean = synchronized(byokLock) {
        val left = byokProfiles.filter { it.id != p.id }.ifEmpty { return false }
        val s = p.suffix
        if (!Keychain.set("byokKey$s", null) || !Keychain.set("byokHeaders$s", null)) return false
        val wasActive = byokProfile.id == p.id
        val kv = mutableListOf<Pair<String, Any?>>("byokUrl$s" to null, "byokModel$s" to null, "byokUserAgent$s" to null,
            "byokProfiles" to LlmProfile.store(left))
        if (wasActive) {
            kv += "byokActive" to left.first().id
            if (ready(left.first().suffix)) kv += "byokSaved" to System.nanoTime()
        }
        put(*kv.toTypedArray())
        true
    }
    val byokUserAgent: String get() = str("byokUserAgent$sfx").orEmpty()
    val byokHeaders: List<Pair<String, String>> get() { rev.intValue; return ByokService.parseHeaders(Keychain.get("byokHeaders$sfx")) }
    /** The saved service read as one (null: the address is not valid); the key may be "" (a service without one). */
    override fun byokService(): ByokService? = synchronized(byokLock) {
        val s = LlmProfile.active(LlmProfile.all(str("byokProfiles")), str("byokActive")).suffix
        val url = ByokTranslator.chatUrl(str("byokUrl$s") ?: "").second ?: return null
        ByokService(url, str("byokModel$s") ?: "", Keychain.get("byokKey$s").orEmpty(), str("byokUserAgent$s") ?: "",
            ByokService.parseHeaders(Keychain.get("byokHeaders$s")))
    }
    override fun byokVariant(url: String, model: String): String? = ByokTranslator.current(str("byokVariant:$url|$model"))
    override fun setByokVariant(url: String, model: String, name: String?) = put("byokVariant:$url|$model" to name)
    /** address and model are there (the key may be left empty, for a free service) */
    val byokReady: Boolean get() = ByokTranslator.chatUrl(byokUrl).second != null && byokModel.isNotEmpty()

    fun listen(l: (String) -> Unit) = listeners.add(l)
    fun unlisten(l: (String) -> Unit) = listeners.remove(l)

    /** Back to the defaults of the caption look (「恢复默认」 on 字幕). */
    fun resetStyle() = put("textSp" to null, "bgAlpha" to null, "palette" to null, "widthFrac" to null, "heightFrac" to null,
        "showJa" to null, "showStats" to null, "keepSentences" to null, "keepChars" to null)

    companion object {
        const val DEFAULT_WIDTH = 0.90f
        // 0.40 on the phone; a desk screen has room for a narrower card (about 300 points on a 1512-wide display)
        const val MIN_WIDTH = 0.20f
        const val DEFAULT_ALPHA = 0.78f
        val prefs by lazy { Prefs(File(dataDir, "settings.json")) }
    }
}

/**
 * Secrets in the login keychain (service "SimulSub"), through /usr/bin/security. Values are written over its stdin
 * (`security -i`), never on a command line other processes could read. Cached: [Prefs] reads keys on every setup.
 */
object Keychain {
    /** Tests point this at a service of their own, so they never touch the app's real secrets. */
    internal var service: String = "SimulSub"
    private val cache = HashMap<String, String?>()

    @Synchronized fun get(account: String): String? = cache.getOrPut(account) {
        if (isWindows) return@getOrPut Dpapi.get(account)
        runCatching {
            val p = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-w")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.readBytes().decodeToString().trimEnd('\n')
            if (p.waitFor() == 0 && out.isNotEmpty()) out else null
        }.getOrNull()
    }

    /** true when the keychain took it (deleting a key that is not there counts as done) */
    @Synchronized fun set(account: String, value: String?): Boolean {
        cache.remove(account)
        val del = value.isNullOrEmpty()
        if (isWindows) return Dpapi.set(account, value?.ifEmpty { null }).also { if (it) cache[account] = value?.ifEmpty { null } }
        val cmd = if (del) "delete-generic-password -s ${q(service)} -a ${q(account)}"
            else "add-generic-password -U -s ${q(service)} -a ${q(account)} -w ${q(value!!)}"
        val ok = runCatching {
            val p = ProcessBuilder("/usr/bin/security", "-i").redirectErrorStream(true).start()
            p.outputStream.use { it.write((cmd + "\n").toByteArray()) }
            val out = p.inputStream.readBytes().decodeToString()
            // a failed command makes `security -i` exit non-zero (a missing item on delete: "could not be found")
            p.waitFor() == 0 || (del && "could not be found" in out)
        }.getOrDefault(false)
        // on failure the cache stays empty: the next read asks the keychain what it really holds
        if (ok) cache[account] = value?.ifEmpty { null }
        return ok
    }

    /** a word for `security -i`: double-quoted, with \ and " escaped (keys are checked to be printable ASCII) */
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Windows' place for [Keychain]: keys.json in [dataDir], each value encrypted with DPAPI for the Windows user (only
 * this user on this computer can read it back). Written whole to a temporary file, then moved over the old one.
 */
private object Dpapi {
    private val file get() = File(dataDir, "keys.json")

    private fun all(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    fun get(account: String): String? = runCatching {
        val b64 = all().optString(account).ifEmpty { return null }
        String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(b64)), Charsets.UTF_8)
    }.getOrNull()

    fun set(account: String, value: String?): Boolean = runCatching {
        val o = all()
        if (value == null) o.remove(account)
        else o.put(account, Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))))
        val tmp = File(dataDir, "keys.json.part")
        tmp.writeText(o.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)
}
