package io.github.korenkrita.simulsub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var preview: CaptionOverlayView
    private lateinit var updater: AppUpdater

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var permList: LinearLayout

    /** Re-reads the style prefs into the sliders and preview; set up in [setupStyle]. */
    private var loadStyle: () -> Unit = {}
    private var modeGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    /** While visible: the overlay toolbar is not focusable, so using it does not pause/resume this screen. */
    private val styleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in STYLE_KEYS) loadStyle()
        // the overlay toolbar flipped the mode: keep the home screen's choice in sync
        if (key == "finalOnly") syncMode()
    }

    private fun syncMode() = modeGroup?.let { g ->
        (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.tag == prefs.finalOnly }?.let { if (g.checkedButtonId != it.id) g.check(it.id) }
    }

    // App-chosen day / night: override the night bit before any resource is read, so values-night and the
    // DayNight theme follow it. "system" leaves the configuration alone.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        val night = when (Prefs(base).theme) {
            "light" -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            "dark" -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        applyOverrideConfiguration(android.content.res.Configuration().apply {
            fontScale = 0f // "unset": a fresh Configuration says 1.0, which would override the user's font size
            uiMode = (base.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_JpSub)
        setContentView(R.layout.activity_main)
        // targetSdk 35 draws edge-to-edge: pad the scroll content by the system bars instead of a black status bar
        val scroll = findViewById<View>(R.id.scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION") // API 29 has no WindowInsets.Type; these still work on 30+
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom); insets
        }
        prefs = Prefs(this)

        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        progress = findViewById(R.id.progress)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        permList = findViewById(R.id.permList)
        setupStyle()
        renderTranslate()
        findViewById<LinearLayout>(R.id.themeBox).apply {
            addView(hint("这个页面用白天还是夜间颜色。不影响悬浮字幕，字幕颜色在「字幕」里选。").apply { setPadding(0, 0, 0, (10 * dpx).toInt()) })
            // recreate re-reads resources with the new mode; the overlay lives in the service and is untouched
            addView(choice(listOf("system" to "跟随系统", "light" to "白天", "dark" to "夜间"), prefs.theme) {
                if (prefs.theme != it) { prefs.theme = it; recreate() }
            })
        }
        // the settings categories, magpie's preferences tabs: one list shown at a time. The saved category is read
        // first, so the switch is built with it checked (a theme change recreates the activity on 页面)
        tab = savedInstanceState?.getInt("tab", 0)?.coerceIn(TABS.indices) ?: 0
        findViewById<android.widget.FrameLayout>(R.id.tabBox).addView(choice(TABS.indices.map { it to TABS[it].second }, tab) { showTab(it) })
        findViewById<View>(R.id.openSettings).setOnClickListener { showSettings(true) }
        findViewById<View>(R.id.closeSettings).setOnClickListener { back() }
        findViewById<View>(R.id.openSize).setOnClickListener { showSize(true) }
        findViewById<TextView>(R.id.aboutVersion).text = "${getString(R.string.app_name)}  版本 ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.openRepo).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            catch (_: android.content.ActivityNotFoundException) { }
        }
        // a theme switch recreates the activity from the settings page: stay there, on the same category / page
        showTab(tab)
        showSettings(savedInstanceState?.getBoolean("settings") == true)
        if (inSettings && savedInstanceState?.getBoolean("sizePage") == true) showSize(true)
        updater = AppUpdater(this)
        // a rotation or theme change is not an entry into the app (unless it cancelled a check that was running)
        configRecreated = savedInstanceState?.getBoolean("configChange") == true
        manualInterrupted = savedInstanceState != null && manualCheckStopped
        manualCheckStopped = false
    }

    private var inSettings = false
    /** Index into [TABS]: the settings category shown. */
    private var tab = 0
    /** The size page (字幕 › 字幕框大小和保留几句) is open over the 字幕 category. */
    private var inSize = false

    private fun showSettings(on: Boolean) {
        inSettings = on
        findViewById<View>(R.id.homePage).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.settingsPage).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (on) {
            loadStyle() // the preview needs the stage's real width, known once the page is laid out
            findViewById<View>(R.id.previewStage).post { loadStyle() }
        } else { showSize(false); renderLang() }
    }

    private fun showTab(i: Int) {
        tab = i
        TABS.forEachIndexed { n, (id, _) -> findViewById<View>(id).visibility = if (n == i && !inSize) View.VISIBLE else View.GONE }
        findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
        if (TABS[i].first == R.id.tabCaption && inSettings) findViewById<View>(R.id.previewStage).post { loadStyle() }
    }

    /** The size page replaces the categories and the 字幕 list; back returns to them. */
    private fun showSize(on: Boolean) {
        inSize = on
        findViewById<View>(R.id.sizePage).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tabBox).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.settingsTitle).text = if (on) "字幕框大小和保留几句" else "设置"
        showTab(if (on) TABS.indexOfFirst { it.first == R.id.tabCaption } else tab)
    }

    /** One step back: size page → settings → home → leave. */
    private fun back(): Boolean = when {
        inSize -> { showSize(false); true }
        inSettings -> { showSettings(false); true }
        else -> false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("settings", inSettings)
        outState.putInt("tab", tab)
        outState.putBoolean("sizePage", inSize)
        // recreated right away for a rotation / theme (no check then), unless that stops a check under way; a state
        // saved for a process the system may end is not this, and the restored app checks again
        outState.putBoolean("configChange", isChangingConfigurations && !updater.checking)
    }

    /** this activity only replaces one a configuration change ended; its first start is not an entry into the app */
    private var configRecreated = false
    /** ... and that change stopped a manual check */
    private var manualInterrupted = false

    override fun onStart() {
        super.onStart()
        // each entry into the app (a launch, or coming back from the background) checks for an update once
        updater.start(configRecreated, manualInterrupted)
        configRecreated = false; manualInterrupted = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!back()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** The home card's two questions, side by side: what is spoken → what the captions should be in. Next sentence. */
    private fun renderLang() {
        val box = findViewById<LinearLayout>(R.id.langBox)
        box.removeAllViews()
        val running = CaptionService.running
        val sources = Lang.SOURCES
        fun weighted(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        // a running session takes a new language from the next sentence
        box.addView(weighted(dropdown(sources.map { it?.zh ?: "不确定（日语或英语）" }, sources.indexOf(prefs.sourceLang).coerceAtLeast(0), true, wrap = true) {
            if (prefs.sourceLang != sources[it]) { prefs.sourceLang = sources[it]; renderTranslate() }
        }).apply { contentDescription = "视频里说的是" })
        box.addView(TextView(this).apply {
            text = "→"; textSize = 16f; setTextColor(getColor(R.color.muted)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0)
        })
        // the presets, the typed language in use (if any), and 其他 to type one; the list is rebuilt after each change,
        // so a cancelled 其他 shows the language in use again
        val presets = Target.PRESETS
        val current = prefs.targetLang
        val targets = if (current in presets) presets else presets + current
        val items = targets.map { it.zh } + "自己填…"
        box.addView(weighted(dropdown(items, targets.indexOf(current), true, wrap = true) {
            if (it == targets.size) askTargetName()
            else if (prefs.targetLang != targets[it]) { prefs.targetLang = targets[it]; renderTranslate(); main.post { renderLang() } }
        }).apply { contentDescription = "字幕翻译成" })
        findViewById<TextView>(R.id.langHint).text = if (running) "视频里说的语言 → 字幕的语言。字幕运行中，改了从下一句开始生效。"
            else "视频里说的语言 → 字幕的语言。知道是哪种语言就选上，翻译会更准。"
    }

    /** 其他（自己填）: a language name typed in, saved with 确定; cancel or a blank name keeps the language in use. */
    private fun askTargetName() {
        val now = prefs.targetLang.takeIf { it.lang == null }?.name ?: ""
        val input = android.widget.EditText(this).apply {
            setText(now); setSelection(now.length); isSingleLine = true; hint = "例如 繁体中文、粤语、文言文"
            filters = arrayOf(android.text.InputFilter.LengthFilter(Target.MAX_NAME))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0); addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("字幕翻译成")
            .setMessage("写上想要的语言，会原样交给你填的 LLM 去翻译。")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                Target.custom(input.text.toString())?.let { if (it != prefs.targetLang) { prefs.targetLang = it; renderTranslate() } }
            }
            // whatever happened, the dropdown shows the language in use again (not 其他)
            .setOnDismissListener { renderLang() }
            .show()
    }

    private fun askProfileName(p: LlmProfile?, now: String, rename: Boolean) {
        val input = android.widget.EditText(this).apply {
            setText(now); setSelection(now.length); isSingleLine = true; hint = "例如 千问、DeepSeek"
            filters = arrayOf(android.text.InputFilter.LengthFilter(LlmProfile.MAX_NAME))
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0); addView(input)
        }
        val err = android.widget.TextView(this).apply {
            textSize = 14f; setTextColor(getColor(R.color.err)); setPadding((22 * dpx).toInt(), (8 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(box); addView(err) }
        val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (rename) "重命名配置" else "新建配置")
            .setView(outer)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定", null)
            .create()
        d.show()
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = input.text.toString()
            val (ok, e) = LlmProfile.name(typed, prefs.byokProfiles, self = p?.id)
            if (ok != null) {
                if (rename) prefs.renameByokProfile(p!!, ok) else prefs.addByokProfile(ok).also { byokDraft = null; byokResult = null }
                d.dismiss(); renderTranslate()
            } else err.text = e
        }
    }

    /**
     * 编辑 a scene ([old]) or 新建 one (null): name, background, terms one per line. 生成术语 asks the LLM API for up to
     * the typed number of terms and only appends them (lines already there, saved or typed, stay as they are).
     * 保存 checks the text and keeps the dialog open on a mistake; 删除 a user's scene, 恢复默认 an edited built-in.
     */
    private fun editScene(old: Scene?) {
        fun area(value: String, hintText: String, lines: Int) = android.widget.EditText(this).apply {
            setText(value); hint = hintText; textSize = 15f; setTextColor(getColor(R.color.text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines; maxLines = 8; gravity = Gravity.TOP or Gravity.START
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dpx).toInt(), (4 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        val name = android.widget.EditText(this).apply {
            setText(old?.label ?: ""); hint = "例如 游戏实况、某主播的直播"; isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(Scene.MAX_LABEL))
        }
        val background = area(old?.background ?: "", "这是什么内容、谁在说话、在聊什么，翻译会参考", 2)
        // the terms: one row of two boxes each (原文 | 译文), the arrow between them is ours
        val termBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun termBox(field: Int) = (0 until termBox.childCount).map { (termBox.getChildAt(it) as LinearLayout).getChildAt(field) as android.widget.EditText }
        fun rows() = termBox(0).zip(termBox(2)) { a, b -> a.text.toString() to b.text.toString() }
        fun addRow(src: String, dst: String) {
            fun box(v: String, h: String) = android.widget.EditText(this).apply {
                setText(v); hint = h; isSingleLine = true; textSize = 15f; setTextColor(getColor(R.color.text))
                filters = arrayOf(android.text.InputFilter.LengthFilter(Scene.MAX_TERM))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(box(src, "原文"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = Scene.TERM_SEP; textSize = 15f; setTextColor(getColor(R.color.muted)); setPadding((6 * dpx).toInt(), 0, (6 * dpx).toInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(box(dst, "译文"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "×"; textSize = 18f; setTextColor(getColor(R.color.text2)); minWidth = 0; minimumWidth = 0
                setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0); contentDescription = "删掉这条"
                setOnClickListener { termBox.removeView(row) }
            })
            termBox.addView(row)
        }
        old?.terms?.forEach { addRow(it.first, it.second) }
        if (termBox.childCount == 0) addRow("", "")
        val addTerm = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ 加一条"; textSize = 14f
            setOnClickListener { if (termBox.childCount < Scene.MAX_TERMS) addRow("", "") }
        }
        val count = android.widget.EditText(this).apply {
            setText("20"); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER; textSize = 15f
            filters = arrayOf(android.text.InputFilter.LengthFilter(3)); contentDescription = "最多生成几条"
        }
        val generate = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "生成术语" }
        val status = hint("")
        form.addView(label("名字", 8)); form.addView(name)
        form.addView(label("背景说明")); form.addView(background)
        // the language the 译文 are in: the terms are used only when the captions are in it
        val captions = prefs.targetLang
        var termsLang = old?.termsLang ?: captions
        val langs = (Target.PRESETS + termsLang + captions).distinct()
        val langHint = hint("")
        fun showLangHint() {
            langHint.text = if (termsLang == captions) "现在字幕翻成${captions.zh}，这些术语会用上。"
                else "现在字幕翻成${captions.zh}，这些${termsLang.zh}术语不会用上；字幕换成${termsLang.zh}才用。"
        }
        showLangHint()
        form.addView(label("术语"))
        form.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "译文是"; textSize = 15f; setTextColor(getColor(R.color.text)); setPadding(0, 0, (12 * dpx).toInt(), 0) })
            addView(dropdown(langs.map { it.zh }, langs.indexOf(termsLang), true) { termsLang = langs[it]; showLangHint() }
                .apply { contentDescription = "术语的译文是哪种语言" }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        form.addView(langHint)
        form.addView(hint("左边写视频里说的词，右边写想要的译法，例如 ボス 和 头目。"))
        form.addView(termBox); form.addView(addTerm)
        form.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(generate)
            addView(TextView(this@MainActivity).apply { text = "最多"; textSize = 15f; setTextColor(getColor(R.color.text2)); setPadding((12 * dpx).toInt(), 0, (4 * dpx).toInt(), 0) })
            addView(count, LinearLayout.LayoutParams((56 * dpx).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply { text = "条"; textSize = 15f; setTextColor(getColor(R.color.text2)) })
        })
        form.addView(status)
        form.addView(hint("「生成术语」用你填的 LLM API，按名字和背景说明想出这类内容的常见词，只往下面加，不改也不删已有的术语。"))

        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "新建内容类型" else "编辑「${old.label}」")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
        when {
            old != null && !old.builtin -> b.setNeutralButton("删除", null)
            old != null && !old.shipped -> b.setNeutralButton("恢复默认", null)
        }
        val dialog = b.show()
        var generating = false

        generate.setOnClickListener {
            if (generating) return@setOnClickListener
            val service = prefs.byokService()
            if (service == null || service.model.isEmpty()) { status.text = "先在上面「LLM API」里填好服务并保存。"; return@setOnClickListener }
            val chat = service.url
            val label = name.text.toString().trim()
            if (label.isEmpty() && background.text.isBlank()) { status.text = "先写名字或背景说明，LLM 才知道要哪类术语。"; return@setOnClickListener }
            val n = count.text.toString().toIntOrNull()
            if (n == null || n < 1 || n > MAX_GENERATE) { status.text = "生成条数填 1 到 $MAX_GENERATE。"; return@setOnClickListener }
            val room = Scene.MAX_TERMS - rows().count { it.first.isNotBlank() || it.second.isNotBlank() }
            if (room <= 0) { status.text = "术语已经有 ${Scene.MAX_TERMS} 条，满了。"; return@setOnClickListener }
            generating = true; generate.isEnabled = false; status.text = "正在生成…（最多一分钟）"
            val model = prefs.byokModel; val stored = prefs.byokVariant(chat, model)
            val bg = background.text.toString(); val src = prefs.sourceLang; val tgt = termsLang
            val have = Scene.sources(rows())
            Thread {
                val (got, err) = generateTerms(service, stored, { prefs.setByokVariant(chat, model, it) }, label, bg, have, minOf(n, room), src, tgt)
                main.post {
                    generating = false; generate.isEnabled = true
                    if (!dialog.isShowing || isDestroyed) return@post
                    // appended to what is in the box now (the user may have typed meanwhile), skipping 原文 now there
                    val now = rows()
                    val (next, added) = appendTerms(now, got.orEmpty())
                    status.text = when {
                        err != null -> "⚠ 没能生成：$err"
                        added == 0 -> "没有生成新的术语（可能都已经有了，或者满了），可以把背景说明写详细些再试。"
                        else -> {
                            // a lone empty row (the one a new scene starts with) gives way to the generated ones
                            if (now.size == 1 && now[0].first.isBlank() && now[0].second.isBlank()) termBox.removeAllViews()
                            next.drop(now.size).forEach { addRow(it.first, it.second) }
                            "加了 $added 条，看一下有没有不对的，再点「保存」。"
                        }
                    }
                }
            }.start()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val (s, err) = Scene.edit(old?.id ?: "u${System.currentTimeMillis()}", name.text.toString(), background.text.toString(), rows(), termsLang)
            if (s == null) { status.text = "⚠ $err"; return@setOnClickListener }
            val list = prefs.scenes
            prefs.scenes = if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
            prefs.scene = s
            dialog.dismiss(); renderTranslate()
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            old ?: return@setOnClickListener
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage(if (old.builtin) "把「${old.label}」恢复成自带的说明和术语？你改过的内容会丢掉。" else "删除「${old.label}」？")
                .setNegativeButton("取消", null)
                .setPositiveButton(if (old.builtin) "恢复默认" else "删除") { _, _ ->
                    val shipped = Scene.DEFAULTS.firstOrNull { it.id == old.id }
                    val inUse = prefs.scene.id == old.id
                    prefs.scenes = if (shipped != null) prefs.scenes.map { if (it.id == old.id) shipped else it }
                        else prefs.scenes.filter { it.id != old.id }
                    if (shipped == null && inUse) prefs.scene = prefs.scenes.first()
                    dialog.dismiss(); renderTranslate()
                }
                .show()
        }
    }

    /** The home card's last row: the LLM API translations go to (or that none is set up yet); opens 设置 › 翻译. */
    private fun renderService() {
        val host = ByokTranslator.chatUrl(prefs.byokUrl).second?.let { android.net.Uri.parse(it).host }
        findViewById<TextView>(R.id.serviceText).text =
            if (prefs.byokReady) {
                val p = prefs.byokProfile.name.takeIf { prefs.byokProfiles.size > 1 }
                "翻译：" + (if (p != null) "$p · " else "") + "${prefs.byokModel} · $host"
            } else "翻译：还没填 LLM API"
        findViewById<View>(R.id.serviceRow).setOnClickListener { openTranslate() }
    }

    /** 设置 › 翻译, where the LLM API is filled in. */
    private fun openTranslate() {
        showSettings(true)
        // through the category switch, so its checked button follows (its listener shows the tab)
        val g = findViewById<android.widget.FrameLayout>(R.id.tabBox).getChildAt(0) as com.google.android.material.button.MaterialButtonToggleGroup
        val want = TABS.indexOfFirst { it.first == R.id.tabTranslate }
        (0 until g.childCount).map { g.getChildAt(it) }.firstOrNull { it.tag == want }?.let { g.check(it.id) }
    }

    override fun onResume() {
        super.onResume()
        loadStyle() // the overlay toolbar may have changed size / opacity / mode meanwhile (no listener while paused)
        renderLang()
        syncMode()
        prefs.listen(styleListener)
        renderPermissions()
        renderTranscripts()
        renderLogs()
        render()
        main.post(ticker)
        updater.resume()
    }

    override fun onPause() {
        super.onPause()
        prefs.unlisten(styleListener)
        main.removeCallbacks(ticker)
        updater.pause()
    }

    override fun onDestroy() {
        // a manual check this recreation stops is redone as manual (also with automatic checks off). Decided here: a
        // stopped activity is recreated without saving its state again, and a process that ends forgets it.
        manualCheckStopped = isChangingConfigurations && updater.checkingManual
        updater.destroy()
        byokTest?.cancel(); byokTest = null
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            // the translation switches are disabled while a session runs
            if (CaptionService.running != translateRunning) { renderTranslate(); renderLang() }
            // a session started or finished (its .part renamed): the list must not keep buttons for files that moved
            val now = SessionLog.list(getExternalFilesDir(null)!!).map { it.name } to SessionLog.activeFiles()
            if (now != listedLogs) renderLogs()
            val ts = Transcript.list(getExternalFilesDir(null)!!)
            if (ts.map { it.name } to ts.firstOrNull { Transcript.isWriting(it) } != listedTranscripts) renderTranscripts()
            main.postDelayed(this, 800)
        }
    }

    // ---- main card -----------------------------------------------------------------------------------------

    /** The last start was refused by the system (foreground service not allowed); kept until the next start tap. */
    private var startRefused = false

    private fun render() {
        progress.visibility = View.GONE
        secondary.visibility = View.GONE
        primary.isEnabled = true
        if (CaptionService.running) {
            val paused = CaptionService.paused
            if (paused) status(R.color.warn, "字幕已暂停", "声音不再识别，字幕窗口保留。点「继续字幕」接着出字幕。")
            else status(R.color.ok, "字幕正在显示", "回到你要看的视频或直播就行。\n点一下字幕，可以调大小、暂停或停止。")
            secondary.visibility = View.VISIBLE
            secondary.text = if (paused) "继续字幕" else "暂停字幕"
            secondary.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
            secondary.setOnClickListener { CaptionService.togglePause(this); main.postDelayed({ render() }, 300) }
            primary.text = "停止字幕"; primary.setIconResource(R.drawable.ic_stop)
            // magpie has no strong red fill: its danger look is red-soft with ink text and a red mark
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.err_soft))
            primary.setTextColor(getColor(R.color.text)); primary.iconTint = ColorStateList.valueOf(getColor(R.color.err))
            primary.setOnClickListener { CaptionService.stop(this); main.postDelayed({ render() }, 300) }
        } else {
            val missingPerm = !hasAudio() || !Settings.canDrawOverlays(this)
            val hint = if (missingPerm) "点下面的按钮，按提示允许下方两项，就能开始。" else "点下面的按钮，再在弹出的窗口里选你要看的 App。"
            renderService()
            if (!prefs.byokReady) {
                // nothing else to set up: without an LLM API there is nothing to start, so the one action goes there
                status(R.color.warn, "先填翻译用的 LLM API", "填好服务地址、模型 ID 和 API Key，点「测试连接」，就能开始。声音识别已经装在 App 里，不用下载。")
                primary.text = "去填写"; primary.setIconResource(R.drawable.ic_settings)
                primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
                onPrimary(R.color.on_brand)
                primary.setOnClickListener { openTranslate() }
                return
            }
            if (startRefused) status(R.color.err, "手机暂时不让字幕启动", "请再点一次开始。如果还是不行，请反馈出现这个提示前做了什么。")
            else status(R.color.brand, if (missingPerm) "还差一步" else "可以开始了", hint)
            primary.text = "开始字幕"; primary.setIconResource(R.drawable.ic_play)
            primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand))
            onPrimary(R.color.on_brand)
            primary.setOnClickListener { start() }
        }
    }

    /** Text and icon colour of the main button: each fill has its own readable foreground (indigo / red, day / night). */
    private fun onPrimary(color: Int) {
        primary.setTextColor(getColor(color)); primary.iconTint = ColorStateList.valueOf(getColor(color))
    }


    /** Busy state: no action, but keep the brand look (a disabled button is grey and hard to read). */
    private fun busy() {
        primary.isEnabled = true
        primary.setOnClickListener(null)
        primary.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand) and 0x00FFFFFF or 0xB3000000.toInt())
    }

    private fun status(color: Int, title: String, body: String) {
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(color)) }
        statusTitle.text = title
        statusBody.text = body
    }

    // ---- permissions ---------------------------------------------------------------------------------------

    private fun hasAudio() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderPermissions() {
        permList.removeAllViews()
        // the home page shows this card only while something required is missing; after that it is just noise
        findViewById<View>(R.id.permCard).visibility =
            if (hasAudio() && Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        permRow("① 听手机里播放的声音", "只听 App 播放出来的声音，不用麦克风", hasAudio(), permList) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_ONLY)
        }
        permRow("② 在屏幕上显示字幕", "让字幕浮在其他 App 上面", Settings.canDrawOverlays(this), permList) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        // optional: on the home card while it shows, and always in Settings (the home card hides once the two are granted)
        val notifBox = findViewById<LinearLayout>(R.id.notifList)
        notifBox.removeAllViews()
        findViewById<View>(R.id.notifSection).visibility = if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val ask = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERM_ONLY) }
            permRow("通知（可以不开）", "在通知栏显示运行状态和「停止」按钮", granted, permList, ask)
            permRow("通知", "在通知栏显示运行状态和「停止」按钮", granted, notifBox, ask)
        }
    }


    private fun permRow(title: String, sub: String, granted: Boolean, parent: LinearLayout, grant: () -> Unit) {
        val dp = resources.displayMetrics.density
        if (parent.childCount > 0) parent.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text)) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(getColor(R.color.text2)) })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (granted) {
            row.addView(TextView(this).apply { text = "✓ 已允许"; textSize = 14f; setTextColor(getColor(R.color.ok)); setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0) })
        } else {
            row.addView(pillButton("去允许") { grant() })
        }
        parent.addView(row)
    }

    // ---- translation / Jev ---------------------------------------------------------------------------------

    private val dpx get() = resources.displayMetrics.density

    /** Small grey caption above a control. */
    private fun label(t: String, top: Int = 14) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text))
        setPadding(0, (top * dpx).toInt(), 0, (8 * dpx).toInt())
    }

    /** Heading of a group inside a settings card. */
    private fun heading(t: String, top: Int = 26) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(getColor(R.color.text)); typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, (top * dpx).toInt(), 0, (4 * dpx).toInt())
    }

    /** One or two lines saying what an option does (text2: these carry privacy / cost explanations, so ≥ 4.5:1). */
    private fun hint(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.text2)); setLineSpacing(2 * dpx, 1f)
        setPadding(0, (6 * dpx).toInt(), 0, 0)
    }

    /** A risk / cost / "takes effect later" note: magpie's amber note, never cut short. */
    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(getColor(R.color.text2)); setLineSpacing(2 * dpx, 1f)
        setBackgroundResource(R.drawable.note_warn)
        setPadding((10 * dpx).toInt(), (8 * dpx).toInt(), (10 * dpx).toInt(), (8 * dpx).toInt())
    }

    /** The hairline between two rows of a list. */
    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.line2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dpx).toInt().coerceAtLeast(1)).apply { marginStart = (14 * dpx).toInt() }
    }

    /** A small grey button at the end of a row (magpie's .text.action on a pill). */
    private fun pillButton(t: String, onClick: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = t; textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (48 * dpx).toInt(); minimumHeight = minHeight
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.pill))
        setOnClickListener { onClick() }
    }

    /** Group title + white list card on the settings page; returns the card's content box. */
    private fun group(root: LinearLayout, title: String): LinearLayout {
        root.addView(TextView(this, null, 0, R.style.SectionTitle).apply { text = title }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dpx).toInt(); bottomMargin = (6 * dpx).toInt()
        })
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dpx).toInt(), (6 * dpx).toInt(), (14 * dpx).toInt(), (14 * dpx).toInt())
        }
        root.addView(com.google.android.material.card.MaterialCardView(this).apply {
            setCardBackgroundColor(getColor(R.color.card)); radius = 12 * dpx; cardElevation = 0f
            strokeColor = getColor(R.color.line); strokeWidth = (1 * dpx).toInt().coerceAtLeast(1)
            addView(inner)
        })
        return inner
    }

    /** A switch with its title, and the description under it. */
    private fun switchRow(box: LinearLayout, title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val s = MaterialSwitch(this).apply {
            text = title; textSize = 15f; setTextColor(getColor(R.color.text)); isChecked = on
            minHeight = (48 * dpx).toInt()
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        box.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(hint(desc).apply { setPadding(0, 0, (56 * dpx).toInt(), 0) })
        return s
    }

    /** A dropdown: magpie's grey pill with a chevron; full width unless placed at the end of a row. With [wrap] the
     *  shown value may take several lines instead of being cut (a row that shares its width with a name). */
    private fun dropdown(items: List<String>, sel: Int, enabled: Boolean, wrap: Boolean = false, onPick: (Int) -> Unit) = android.widget.Spinner(this).apply {
        adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getView(p, c, g) as TextView).apply {
                    textSize = 14f; setTextColor(getColor(R.color.text)); minHeight = (48 * dpx).toInt()
                    setPadding((12 * dpx).toInt(), (6 * dpx).toInt(), (8 * dpx).toInt(), (6 * dpx).toInt())
                    if (wrap) {
                        // the platform item has a fixed list-item height: let a wrapped value grow it
                        isSingleLine = false; maxLines = Int.MAX_VALUE; ellipsize = null
                        layoutParams = (layoutParams ?: android.view.ViewGroup.LayoutParams(0, 0)).apply {
                            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT; height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    }
                }
            override fun getDropDownView(p: Int, c: View?, g: android.view.ViewGroup) =
                (super.getDropDownView(p, c, g) as TextView).apply { textSize = 15f; minHeight = (48 * dpx).toInt() }
        }
        setBackgroundResource(R.drawable.spinner_pill)
        setPadding(0, 0, (26 * dpx).toInt(), 0) // room for the chevron
        setSelection(sel, false)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        minimumHeight = (48 * dpx).toInt()
    }


    /** A segment of [choice], recoloured to magpie's .seg: an unchosen option is plain text on the grey track (no
     *  fill, no outline); the chosen one is the card-coloured thumb. No stroke at all: the group pulls each button
     *  over its neighbour by the stroke width on every measure, which let the thumb cover the next option's edge.
     *  The group also squares the inner corners on every measure; every segment keeps 8dp. */
    private class SegButton(ctx: android.content.Context) : MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) {
        init { strokeWidth = 0 }
        override fun setShapeAppearanceModel(m: com.google.android.material.shape.ShapeAppearanceModel) =
            super.setShapeAppearanceModel(m.toBuilder().setAllCornerSizes(8 * resources.displayMetrics.density).build())
    }

    /** Single-choice row of segmented buttons (magpie's .seg: a grey track, the chosen option a raised thumb). */
    private fun <T> choice(options: List<Pair<T, String>>, current: T, onPick: (T) -> Unit) =
        com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            // a LinearLayout lines its children up on the text baseline: a button whose label shrank would sit lower
            isBaselineAligned = false
            setBackgroundResource(R.drawable.seg_track)
            setPadding((3 * dpx).toInt(), 0, (3 * dpx).toInt(), 0)
            options.forEach { (v, t) ->
                val b = SegButton(this@MainActivity).apply {
                    id = View.generateViewId(); text = t; tag = v; textSize = 14f; minHeight = (48 * dpx).toInt()
                    // narrow phones: the label shrinks (down to 10 sp) until the laid-out text is uncut, instead of
                    // being cut to "在…"; judged on the real layout, so the button's paddings and stroke count. Still
                    // cut at 10 sp (very large system font): it wraps over as many lines as it needs, never cut.
                    isAllCaps = false
                    setPadding((4 * dpx).toInt(), paddingTop, (4 * dpx).toInt(), paddingBottom)
                    val min = 10 * resources.displayMetrics.scaledDensity
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val b = v as MaterialButton
                        val lay = b.layout ?: return@addOnLayoutChangeListener
                        val cut = lay.lineCount > b.maxLines || (0 until lay.lineCount).any { lay.getEllipsisCount(it) > 0 }
                        if (!cut) return@addOnLayoutChangeListener
                        if (b.textSize > min) b.post {
                            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, maxOf(min, b.textSize - resources.displayMetrics.scaledDensity / 2))
                        } else b.post { b.maxLines = Int.MAX_VALUE; b.ellipsize = null }
                    }
                }
                addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                // the group's addView sets one line and an ellipsis on every button: anything set before is lost
                b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.END
                if (v == current) check(b.id)
            }
            addOnButtonCheckedListener { g, id, checked ->
                @Suppress("UNCHECKED_CAST") if (checked) onPick(g.findViewById<View>(id).tag as T)
            }
        }

    /**
     * The translation service, scene, display mode and Jev. While running: the two Jev switches wait for the next start,
     * the display mode applies at once, everything else from the next sentence (CaptionService rebuilds its setup).
     */
    private fun renderTranslate() {
        val root = findViewById<LinearLayout>(R.id.translateList)
        root.removeAllViews()
        val running = CaptionService.running
        translateRunning = running
        if (running) root.addView(note("字幕正在运行：内容类型、结合上文、去掉重复的字、LLM API（切换配置或点保存后）和 Jev 在线模型服务、key 改了从下一句开始生效；智能断句和检查译文从下次开始字幕时生效；显示方式马上生效。"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dpx).toInt() })

        // -- the LLM API the captions are translated with
        var box = group(root, "LLM API")
        renderByok(box, running)

        box = group(root, "内容")
        box.addView(label("内容类型"))
        // the scenes: the built-ins and the user's own; 编辑 the one in use, 新建 another (a running session: from the
        // next sentence)
        val scenes = prefs.scenes
        val scene = prefs.scene
        box.addView(dropdown(scenes.map { it.label }, scenes.indexOfFirst { it.id == scene.id }.coerceAtLeast(0), true) {
            if (scenes[it].id != prefs.scene.id) { prefs.scene = scenes[it]; main.post { renderTranslate() } }
        }.apply { contentDescription = "内容类型" })
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(pillButton("编辑") { editScene(scene) })
            addView(pillButton("新建") { editScene(null) }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dpx).toInt() })
        box.addView(hint(scene.summary(prefs.targetLang)))

        // translation context: on/off and how many finished sentences (a running session: from the next sentence)
        val ctxCount = label("")
        val ctxSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 1f; valueTo = Pipeline.MAX_PREVIOUS_SENTENCES.toFloat(); stepSize = 1f; isTickVisible = false
            value = prefs.contextSentences.toFloat()
        }
        fun showCtx() {
            ctxCount.text = "参考前面 ${prefs.contextSentences} 句"
            ctxCount.visibility = if (prefs.contextOn) View.VISIBLE else View.GONE
            ctxSlider.visibility = ctxCount.visibility
        }
        switchRow(box, "结合上文来翻译", "翻译每句话时，把前面刚说过的几句一起交给翻译参考，帮助它理解人称、指代和省略的内容。参考的句子越多，翻译要处理的文字越多，可能更慢。",
            prefs.contextOn) { prefs.contextOn = it; showCtx() }
        box.addView(ctxCount)
        box.addView(ctxSlider)
        ctxSlider.addOnChangeListener { _, v, user -> if (user) { prefs.contextSentences = v.toInt(); showCtx() } }
        showCtx()

        // -- how captions appear
        box = group(root, "字幕怎么出现")
        modeGroup = choice(listOf(false to "边听边出", true to "说完再出"), prefs.finalOnly) { if (prefs.finalOnly != it) prefs.finalOnly = it }
        box.addView(modeGroup, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dpx).toInt()
        })
        box.addView(hint("「边听边出」：一边说一边显示，灰色的字还会修改，说完一句后变成正式字幕。「说完再出」：每句话说完才显示，画面更干净，但会晚一点。字幕上的工具栏也能随时切换。"))
        switchRow(box, "去掉重复的字", "一句话分段识别时，接口处偶尔会把同一段话识别两遍。打开后自动去掉多出来的那一遍；如果说话人真的重复说了，也可能被去掉。",
            prefs.dedupeJoin) { prefs.dedupeJoin = it }

        // -- Jev (optional cloud judge)
        box = group(root, "在线 Decisions 模型辅助（可选，需要联网）")
        val jev = switchRow(box, "智能断句", "由 Jev 模型判断一句话是否真的说完，减少把一句话切成两半的情况。", prefs.jevOn) {}
        val jevCheck = switchRow(box, "检查译文", "由 Jev 模型检查每句译文是否把意思译反、漏了否定等，发现问题就重新翻译这一句。打开后每句字幕会稍晚出现。", prefs.jevCheck) {}
        val jevBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(jevBox)
        fun renderJev() {
            jevBox.removeAllViews()
            val used = prefs.jevOn || prefs.jevCheck // the provider and key serve both features
            jevBox.visibility = if (used) View.VISIBLE else View.GONE
            if (!used) return
            val p = prefs.jevProvider
            jevBox.addView(label("Jev 在线模型服务"))
            // a full-width dropdown: three segmented buttons do not fit a phone's width with readable names
            jevBox.addView(dropdown(JevProvider.entries.map { "${it.label}（${it.note}）" }, p.ordinal, true) {
                if (prefs.jevProvider.ordinal != it) { prefs.jevProvider = JevProvider.entries[it]; main.post { renderJev() } }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (!p.needsKey) {
                jevBox.addView(hint("不用填 key，打开就能用。这是免费的公共服务，可能随时限速或停用；服务不可用时，字幕照常显示，只是不做辅助。只发送识别出的文字和译文，不发送声音。"))
                return
            }
            val saved = prefs.jevKey(p)
            jevBox.addView(label(if (saved != null) "${p.label} 的 key：已保存（…${saved.takeLast(4)}）" else "${p.label} 的 key：还没填"))
            val input = android.widget.EditText(this).apply {
                hint = if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true; textSize = 16f; setTextColor(getColor(R.color.text))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            jevBox.addView(input)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "清除"; textSize = 16f; isEnabled = saved != null
                setOnClickListener { prefs.setJevKey(p, null); renderJev() }
            })
            row.addView(MaterialButton(this).apply {
                text = "保存"; textSize = 16f
                setOnClickListener {
                    val k = input.text.toString().trim()
                    when {
                        k.isEmpty() -> {}
                        // a key with spaces/newlines/non-ASCII would be rejected by HTTP (and echoed in its error)
                        !Jev.validKey(k) -> Toast.makeText(this@MainActivity, "key 里有空格、换行或特殊字符，请重新复制", Toast.LENGTH_LONG).show()
                        else -> { prefs.setJevKey(p, k); input.setText(""); renderJev() }
                    }
                }
            })
            jevBox.addView(row)
            jevBox.addView(hint("只发送识别出的文字和译文，不发送声音。key 加密保存在这台手机上，不会写进使用记录。"))
        }
        jev.setOnCheckedChangeListener { _, on -> prefs.jevOn = on; renderJev() }
        jevCheck.setOnCheckedChangeListener { _, on -> prefs.jevCheck = on; renderJev() }
        renderJev()
    }

    /** The running BYOK test, so a second tap, a new render or leaving the screen does not show a stale result. */
    @Volatile private var byokTest: ByokTranslator? = null

    /** BYOK: the user's OpenAI-compatible service; URL, key, model id and a test that also finds the thinking switch. */
    private fun renderByok(box: LinearLayout, running: Boolean) {
        val testing = byokTest != null
        val profiles = prefs.byokProfiles
        val active = prefs.byokProfile
        box.addView(label("配置"))
        box.addView(dropdown(profiles.map { it.name }, profiles.indexOfFirst { it.id == active.id }.coerceAtLeast(0), !testing) {
            if (profiles[it].id != prefs.byokProfile.id) {
                prefs.useByokProfile(profiles[it]); byokDraft = null
                byokResult = "已切换到「${profiles[it].name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
                main.post { renderTranslate() }
            }
        }.apply { contentDescription = "配置" })
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(pillButton("重命名") { askProfileName(active, active.name, rename = true) }.apply { isEnabled = !testing })
            addView(pillButton("新建") { askProfileName(null, "", rename = false) }.apply {
                isEnabled = !testing && profiles.size < LlmProfile.MAX_PROFILES
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
            if (profiles.size > 1) addView(pillButton("删除") {
                confirmDelete("删除「${active.name}」？", "这个配置里的服务地址、模型 ID、API Key 和高级设置都会删掉。") {
                    prefs.deleteByokProfile(active); byokDraft = null
                    byokResult = "已切换到「${prefs.byokProfile.name}」，字幕从下一句开始用这个配置。".takeIf { running && prefs.byokReady }
                    renderTranslate()
                }
            }.apply {
                isEnabled = !testing; setTextColor(getColor(R.color.err))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * dpx).toInt() })
        box.addView(hint("填一个 OpenAI 兼容接口的 LLM 服务来翻译字幕，只发送识别出的文字，不发送声音。\n模型要求：必须能关闭思考（深度思考 / reasoning），否则每句都要等很久；尽量选回复速度快的模型，字幕才跟得上。"))
        fun field(title: String, value: String, hintText: String, password: Boolean = false): android.widget.EditText {
            box.addView(label(title))
            return android.widget.EditText(this).apply {
                setText(value); hint = hintText; isSingleLine = true; textSize = 16f; setTextColor(getColor(R.color.text))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                box.addView(this)
            }
        }
        val d = byokDraft ?: ByokDraft(prefs.byokUrl, prefs.byokModel).also { byokDraft = it }
        val url = field("服务地址（OpenAI 兼容）", d.url, "例如 https://api.deepseek.com，结尾有没有 /v1 都可以")
        val model = field("模型 ID", d.model, "例如 deepseek-chat")
        val saved = prefs.byokKey?.takeUnless { d.clearKey }
        val key = field(when {
            saved != null -> "API Key：已保存（…${saved.takeLast(4)}）"
            d.clearKey -> "API Key：保存后清除（不需要 key 的服务可以空着）"
            else -> "API Key：没填（不需要 key 的服务可以空着）"
        }, d.key, if (saved != null) "输入新的 key 来替换" else "在这里粘贴 key", password = true)
        // kept in the draft as typed, so re-rendering (any setting on this page) does not lose it
        fun keep() { d.url = url.text.toString(); d.model = model.text.toString(); d.key = key.text.toString() }
        for (f in listOf(url, model, key)) f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = keep()
        })
        val result = hint("")
        // save what is in the boxes; null when something is wrong (and says what)
        fun save(): String? {
            val u = url.text.toString().trim(); val m = model.text.toString().trim(); val k = key.text.toString().trim()
            val (bad, _) = ByokTranslator.chatUrl(u)
            when {
                bad != null -> { result.text = "⚠ $bad"; return null }
                m.isEmpty() -> { result.text = "⚠ 还没填模型 ID"; return null }
                m.any { it.isWhitespace() } -> { result.text = "⚠ 模型 ID 里不要有空格"; return null }
                k.isNotEmpty() && !Jev.validKey(k) -> { result.text = "⚠ key 里有空格、换行或特殊字符，请重新复制"; return null }
            }
            // one edit; a running session switches to the saved service from the next sentence, as one change
            prefs.saveByok(u, m, k.ifEmpty { if (d.clearKey) "" else null }, d.userAgent, d.headers)
            byokDraft = null; key.setText("")
            return ByokTranslator.chatUrl(u).second
        }
        // while a test runs the boxes are locked, so its result always belongs to what they show
        // Running: "保存" switches the session to what was saved from the next sentence (it tests a new model itself),
        // so the test button is off (two probes would bill twice) and so is clearing the key (the session needs one).
        listOf(url, model, key).forEach { it.isEnabled = !testing }
        // on a line of its own: four buttons do not fit a phone's width
        val extra = listOfNotNull((d.userAgent ?: prefs.byokUserAgent).ifEmpty { null }?.let { "自定义 User-Agent" },
            (d.headers ?: prefs.byokHeaders).size.takeIf { it > 0 }?.let { "$it 个请求头" },
            "未保存".takeIf { d.userAgent != null || d.headers != null }).joinToString("、")
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "高级设置" + if (extra.isEmpty()) "（User-Agent、请求头）" else "（$extra）"
            textSize = 15f; isEnabled = !testing; isAllCaps = false
            setOnClickListener { editByokAdvanced(d) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            // a draft change like the rest: saved (and a running session switched) only by 保存
            text = "清除 key"; textSize = 16f; isEnabled = saved != null && !testing
            setOnClickListener { d.clearKey = true; d.key = ""; renderTranslate() }
        })
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "保存"; textSize = 16f; isEnabled = !testing
            setOnClickListener {
                if (save() != null) {
                    if (running) byokResult = "已保存，字幕从下一句开始用新的设置；第一次用这个模型时会先自动测试怎样关闭思考。"
                    renderTranslate()
                }
            }
        })
        val test = MaterialButton(this).apply { text = if (testing) "测试中…" else "测试连接"; textSize = 16f; isEnabled = !running && !testing }
        row.addView(test)
        box.addView(row)
        box.addView(result)
        val chat = ByokTranslator.chatUrl(prefs.byokUrl).second
        val known = chat?.let { prefs.byokVariant(it, prefs.byokModel) }
        result.text = byokResult?.also { byokResult = null } ?: when {
            testing -> "正在依次尝试关闭思考的参数（最多 6 次，每次最多 8 秒，每次都会计费）…"
            chat == null || prefs.byokModel.isEmpty() -> "填好地址、模型 ID 和 key 后点「测试连接」。第一次使用时会依次试最多 6 种关闭思考的参数（每种是一次计费请求），找到没检测到思考的就记住。"
            known == ByokTranslator.NONE_WORKED -> "⚠ 上次测试：已知的参数都关不掉这个模型的思考，翻译可能很慢。仍然可以使用。"
            known != null -> "上次测试可用（用的参数：${ByokTranslator.variant(known)?.label}，那次没检测到思考）。实际请求地址：$chat"
            else -> "还没测试，第一次开始字幕时会自动测。实际请求地址：$chat"
        }
        test.setOnClickListener {
            val chatUrl = save() ?: return@setOnClickListener
            val m = prefs.byokModel; val service = prefs.byokService() ?: return@setOnClickListener
            val t = ByokTranslator(service, byokSystem(prefs.scene, prefs.sourceLang, prefs.targetLang), null,
                maxTokens = mtMaxTokens(prefs.targetLang))
            byokTest?.cancel(); byokTest = t
            renderTranslate() // shows "testing" and locks the boxes
            Thread {
                val steps = mutableListOf<ByokTranslator.Step>()
                val v = t.probe(steps)
                main.post {
                    if (byokTest !== t || isDestroyed) return@post // cancelled (captions started), or the screen is gone
                    byokTest = null
                    val ok = steps.firstOrNull { it.variant === v }?.result
                    val none = v == null && t.exhausted(steps)
                    when {
                        v != null -> prefs.setByokVariant(chatUrl, m, v.name)
                        none -> prefs.setByokVariant(chatUrl, m, ByokTranslator.NONE_WORKED)
                    }
                    // shown once by the next render
                    byokResult = when {
                        ok != null -> "✓ 可以用。参数：${v!!.label}（这次没检测到思考，已记住）；收到第一个字 ${ok.firstContentMs ?: "?"} ms" +
                            (if ((ok.firstContentMs ?: 0) > FIRST_CONTENT_MS) "（超过 ${FIRST_CONTENT_MS / 1000} 秒，翻译时这句会判为失败）" else "") + "。\n测试译文：${ok.text.take(60)}"
                        none -> "⚠ 连得上，但试过的 ${steps.size} 种参数都没能关掉思考，翻译可能很慢或经常失败。仍然可以使用。"
                        else -> "✗ 不能用：${steps.lastOrNull()?.result?.let { ByokTranslator.why(it) } ?: "没有回应"}"
                    }
                    renderTranslate()
                }
            }.start()
        }
    }
    /** The finished test's outcome, shown once by the next render. */
    private var byokResult: String? = null
    /**
     * What the LLM API boxes hold but 保存 has not saved yet, so a re-render (高级设置 closing, a test) keeps it:
     * address, model, a typed key, "clear the saved key", and the 高级设置 (null: unchanged). All of it is saved by one
     * 保存, so a running session never pairs one service's headers or key with another's address.
     */
    private class ByokDraft(var url: String, var model: String) {
        var key = ""; var clearKey = false
        var userAgent: String? = null; var headers: List<Pair<String, String>>? = null
    }
    private var byokDraft: ByokDraft? = null

    /**
     * 高级设置 of the LLM API: the User-Agent (empty = ours) and extra request headers, one row of two boxes each. 好
     * checks them and puts them in [draft]; the LLM API's 保存 saves them with the rest of the service (the headers
     * sealed like the key), and a running session takes them from the next sentence.
     */
    private fun editByokAdvanced(draft: ByokDraft) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * dpx).toInt(), (4 * dpx).toInt(), (22 * dpx).toInt(), 0)
        }
        fun box(v: String, h: String) = android.widget.EditText(this).apply {
            setText(v); hint = h; isSingleLine = true; textSize = 15f; setTextColor(getColor(R.color.text))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val ua = box(draft.userAgent ?: prefs.byokUserAgent, ByokTranslator.defaultUserAgent)
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rows() = (0 until rowsBox.childCount).map {
            val r = rowsBox.getChildAt(it) as LinearLayout
            (r.getChildAt(0) as android.widget.EditText).text.toString().trim() to (r.getChildAt(2) as android.widget.EditText).text.toString().trim()
        }
        fun addRow(k: String, v: String) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(box(k, "名字"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = ":"; textSize = 15f; setTextColor(getColor(R.color.muted)); setPadding((6 * dpx).toInt(), 0, (6 * dpx).toInt(), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            row.addView(box(v, "值"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "×"; textSize = 18f; setTextColor(getColor(R.color.text2)); minWidth = 0; minimumWidth = 0
                setPadding((10 * dpx).toInt(), 0, (10 * dpx).toInt(), 0); contentDescription = "删掉这个请求头"
                setOnClickListener { rowsBox.removeView(row) }
            })
            rowsBox.addView(row)
        }
        (draft.headers ?: prefs.byokHeaders).forEach { addRow(it.first, it.second) }
        if (rowsBox.childCount == 0) addRow("", "")
        val status = hint("")
        form.addView(label("User-Agent", 8)); form.addView(ua)
        form.addView(hint("空着就用 ${ByokTranslator.defaultUserAgent}。有的服务会拦下不认识的 User-Agent，例如 B 站 Index 的免费接口要求填 Index-Translate-Client/1.0。"))
        form.addView(label("自定义请求头"))
        form.addView(hint("左边写名字，右边写值，每个请求都会带上。值和 key 一样加密保存，使用记录里只记名字。"))
        form.addView(rowsBox)
        form.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "+ 加一个"; textSize = 14f
            setOnClickListener { if (rowsBox.childCount < ByokService.MAX_HEADERS) addRow("", "") }
        })
        form.addView(status)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("LLM API 高级设置")
            .setView(android.widget.ScrollView(this).apply { addView(form) })
            .setNegativeButton("取消", null)
            .setPositiveButton("好", null)
            .show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val agent = ua.text.toString().trim()
            val headers = rows().filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
            val err = ByokService.badAdvanced(agent, headers)
            if (err != null) { status.text = "⚠ $err"; return@setOnClickListener }
            // into the draft: saved with the address, model and key by the LLM API's 保存
            draft.userAgent = agent; draft.headers = headers
            byokResult = "高级设置改好了，点「保存」才会生效。"
            dialog.dismiss(); renderTranslate()
        }
    }

    // ---- session logs --------------------------------------------------------------------------------------

    private fun renderLogs() {
        val box = findViewById<LinearLayout>(R.id.logList)
        box.removeAllViews()
        val files = SessionLog.list(getExternalFilesDir(null)!!)
        val active = SessionLog.activeFiles()
        listedLogs = files.map { it.name } to active
        // off by default; the switch takes effect from the next start (a running session keeps what it began with)
        switchRow(box, "记录每次使用", "打开后，每次开始字幕会在手机上留一份文字记录：识别结果、翻译的提示词和译文、在线 Decisions 模型辅助的请求和回答，不含声音和 key。只有你点「分享」才会离开手机，出问题时可以发给开发者帮助排查。从下次开始字幕起生效。",
            prefs.keepLogs) { prefs.keepLogs = it; renderLogs() }
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = if (prefs.keepLogs) "还没有记录。" else "还没有记录。现在不记录，打开上面的开关后才会记录。"
                textSize = 15f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
            })
            return
        }
        val dp = resources.displayMetrics.density
        // all logs stay reachable: beyond 5 the list scrolls inside a fixed-height box instead of growing the page
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // session-20261010-151200[-1].jsonl[.part] → 2026-10-10 15:12:00
            val n = f.name.removePrefix("session-").substringBefore(".jsonl")
            val title = if (n.length >= 15) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}${n.substring(15)}" else f.name
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.text)) })
            val complete = SessionLog.complete(f)
            texts.addView(TextView(this).apply {
                // .part: still running, or the session failed / was killed before the log was finished
                text = "%.1f KB".format(f.length() / 1024.0) + when {
                    complete -> ""
                    f in active -> " · 正在记录，结束后可以分享"
                    else -> " · 没有正常结束"
                }
                textSize = 13.5f; setTextColor(getColor(R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "删除"; setTextColor(getColor(R.color.err)); isEnabled = f !in active
                minWidth = 0; minimumWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                setOnClickListener { confirmDelete("删除 $title 的记录？", "删除后不能恢复。") {
                    if (!SessionLog.delete(f)) Toast.makeText(this@MainActivity, "删除失败：记录正在写入或文件被占用", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "分享"; isEnabled = f !in active; setOnClickListener { share(f) }
            })
            rows.addView(row)
        }
        if (files.size > LOGS_BEFORE_SCROLL) {
            val scroller = object : android.widget.ScrollView(this) {
                // the page is a ScrollView too: keep the gesture in this list while it can scroll
                override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
                    return super.onTouchEvent(e)
                }
                override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.onInterceptTouchEvent(e)
                }
            }.apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(rows) }
            // about 5½ rows: the cut-off row shows there is more
            box.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (LOGS_BEFORE_SCROLL * 58 * dp + 28 * dp).toInt()))
        } else box.addView(rows)
        val deletable = files.count { it !in active }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "共 ${files.size} 个，" + files.sumOf { it.length() }.let { if (it < 1048576) "%.1f KB".format(it / 1024.0) else "%.1f MB".format(it / 1048576.0) }
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "全部清空"; setTextColor(getColor(R.color.err)); isEnabled = deletable > 0
                setOnClickListener { confirmDelete("删除全部 $deletable 个记录？", "正在记录的不会删除。删除后不能恢复。") {
                    val left = SessionLog.deleteAll(getExternalFilesDir(null)!!)
                    if (left > 0) Toast.makeText(this@MainActivity, "还剩 $left 个（正在记录或无法删除）", Toast.LENGTH_SHORT).show()
                    renderLogs()
                } }
            })
        })
    }

    // ---- transcripts ---------------------------------------------------------------------------------------

    private fun renderTranscripts() {
        val box = findViewById<LinearLayout>(R.id.transcriptList)
        box.removeAllViews()
        val root = getExternalFilesDir(null)!!
        val files = Transcript.list(root)
        listedTranscripts = files.map { it.name } to files.firstOrNull { Transcript.isWriting(it) }
        val dp = resources.displayMetrics.density
        switchRow(box, "保存字幕文本", "每句说完定稿的原文和译文，按时间存成一份纯文本，可以在这里查看或分享。和下面的「记录每次使用」无关。从下次开始字幕起生效。",
            prefs.keepTranscript) { prefs.keepTranscript = it; renderTranscripts() }
        if (files.isEmpty()) {
            box.addView(TextView(this).apply {
                text = if (prefs.keepTranscript) "还没有字幕文本。" else "还没有字幕文本。现在不保存，打开上面的开关后才会保存。"
                textSize = 15f; setTextColor(getColor(R.color.text2))
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
            return
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (f in files) {
            val writing = Transcript.isWriting(f)
            val title = transcriptTitle(f)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(getColor(R.color.text)) })
            texts.addView(TextView(this).apply {
                text = "%.1f KB".format(f.length() / 1024.0) + if (writing) " · 正在保存" else ""
                textSize = 13.5f; setTextColor(getColor(R.color.text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "删除"; setTextColor(getColor(R.color.err)); isEnabled = !writing
                minWidth = 0; minimumWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                setOnClickListener { confirmDelete("删除 $title 的字幕文本？", "删除后不能恢复。") {
                    if (!Transcript.delete(f)) Toast.makeText(this@MainActivity, "删除失败：正在保存或文件被占用", Toast.LENGTH_SHORT).show()
                    renderTranscripts()
                } }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "查看"; setOnClickListener { showTranscript(f) }
            })
            rows.addView(row)
        }
        if (files.size > LOGS_BEFORE_SCROLL) {
            val scroller = object : android.widget.ScrollView(this) {
                // the page is a ScrollView too: keep the gesture in this list while it can scroll
                override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
                    return super.onTouchEvent(e)
                }
                override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                    if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
                    return super.onInterceptTouchEvent(e)
                }
            }.apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(rows) }
            box.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (LOGS_BEFORE_SCROLL * 58 * dp + 28 * dp).toInt()))
        } else box.addView(rows)
        val deletable = files.count { !Transcript.isWriting(it) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "共 ${files.size} 份，" + files.sumOf { it.length() }.let { if (it < 1048576) "%.1f KB".format(it / 1024.0) else "%.1f MB".format(it / 1048576.0) }
                textSize = 12.5f; setTextColor(getColor(R.color.text2))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "全部清空"; setTextColor(getColor(R.color.err)); isEnabled = deletable > 0
                setOnClickListener { confirmDelete("删除全部 $deletable 份字幕文本？", "正在保存的不会删除。删除后不能恢复。") {
                    val left = Transcript.deleteAll(root)
                    if (left > 0) Toast.makeText(this@MainActivity, "还剩 $left 份（正在保存或无法删除）", Toast.LENGTH_SHORT).show()
                    renderTranscripts()
                } }
            })
        })
    }

    /** 同传字幕-20261021-140312[-1].txt → 2026-10-21 14:03:12 */
    private fun transcriptTitle(f: java.io.File): String {
        val n = f.name.removePrefix("同传字幕-").removeSuffix(".txt")
        return if (n.length >= 15) "${n.substring(0, 4)}-${n.substring(4, 6)}-${n.substring(6, 8)} ${n.substring(9, 11)}:${n.substring(11, 13)}:${n.substring(13, 15)}${n.substring(15)}" else f.name
    }

    /** The text in a dialog, selectable; one being written shows what it has so far. */
    private fun showTranscript(f: java.io.File) {
        val text = runCatching { f.readText() }.getOrElse {
            Toast.makeText(this, "这份字幕文本已经不在了", Toast.LENGTH_SHORT).show(); renderTranscripts(); return
        }
        val dp = resources.displayMetrics.density
        val view = android.widget.ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                this.text = text; textSize = 15f; setTextColor(getColor(R.color.text)); setTextIsSelectable(true)
                setLineSpacing(0f, 1.15f)
                setPadding((22 * dp).toInt(), (8 * dp).toInt(), (22 * dp).toInt(), (8 * dp).toInt())
            })
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(transcriptTitle(f))
            .setView(view)
            .setNegativeButton("关闭", null)
            .setPositiveButton("分享") { _, _ -> shareTranscript(f) }
            .show()
    }

    private fun shareTranscript(f: java.io.File) {
        if (!f.isFile) { Toast.makeText(this, "这份字幕文本已经不在了", Toast.LENGTH_SHORT).show(); renderTranscripts(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享 ${f.name}"))
    }

    private var listedTranscripts: Pair<List<String>, java.io.File?>? = null

    private fun confirmDelete(title: String, msg: String, ok: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title).setMessage(msg)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> ok() }
            .show()
    }

    private var listedLogs: Pair<List<String>, Set<java.io.File>>? = null
    private var translateRunning = false

    private fun share(f: java.io.File) {
        // the list may be up to one tick old: never hand out a URI for a file that is being written or no longer exists
        if (SessionLog.isActive(f) || !f.isFile) {
            Toast.makeText(this, "这个记录刚刚结束或已变动，请重新选择", Toast.LENGTH_SHORT).show(); renderLogs(); return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.logs", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream" // TG and file managers keep .jsonl as a file instead of pasting text
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享 ${f.name}"))
    }

    // ---- subtitle style ------------------------------------------------------------------------------------

    private fun setupStyle() {
        preview = CaptionOverlayView(this, interactive = false)
        findViewById<FrameLayout>(R.id.previewHolder).addView(preview)
        val size = findViewById<Slider>(R.id.sliderSize)
        val alpha = findViewById<Slider>(R.id.sliderAlpha)
        val width = findViewById<Slider>(R.id.sliderWidth)
        val ja = findViewById<MaterialSwitch>(R.id.switchJa)
        val stats = findViewById<MaterialSwitch>(R.id.switchStats)
        val lSize = findViewById<TextView>(R.id.labelSize)
        val lAlpha = findViewById<TextView>(R.id.labelAlpha)
        val lWidth = findViewById<TextView>(R.id.labelWidth)
        val height = findViewById<Slider>(R.id.sliderHeight)
        val lHeight = findViewById<TextView>(R.id.labelHeight)
        val keepS = findViewById<Slider>(R.id.sliderKeepSentences)
        val keepC = findViewById<Slider>(R.id.sliderKeepChars)
        val lKeepS = findViewById<TextView>(R.id.labelKeepSentences)
        val lKeepC = findViewById<TextView>(R.id.labelKeepChars)
        val keepHint = findViewById<TextView>(R.id.keepHint)
        val paletteRow = findViewById<LinearLayout>(R.id.paletteRow)
        // the fade says "there is more to the right"; once scrolled to the end there is nothing left to hint at
        val paletteScroll = findViewById<android.widget.HorizontalScrollView>(R.id.paletteScroll)
        val paletteFade = findViewById<View>(R.id.paletteFade)
        fun updatePaletteFade() { paletteFade.visibility = if (paletteScroll.canScrollHorizontally(1)) View.VISIBLE else View.GONE }
        paletteScroll.setOnScrollChangeListener { _, _, _, _, _ -> updatePaletteFade() }
        val dpx = resources.displayMetrics.density
        // one chip per palette (magpie's .chip): a round sample of its background and text colour, then its name;
        // the chosen one gets an ink outline
        fun renderPalettes() {
            paletteRow.removeAllViews()
            for (p in CaptionOverlayView.PALETTES) {
                val sel = p.id == CaptionOverlayView.palette(prefs.palette).id
                paletteRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    contentDescription = p.label + if (sel) "（已选）" else ""
                    minimumHeight = (48 * dpx).toInt()
                    setPadding((5 * dpx).toInt(), 0, (12 * dpx).toInt(), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 22 * dpx; setColor(getColor(R.color.card))
                        setStroke(((if (sel) 2 else 1) * dpx).toInt().coerceAtLeast(1), getColor(if (sel) R.color.text else R.color.line))
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "字"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(p.zh); typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(p.bg or 0xFF000000.toInt()); setStroke((1 * dpx).toInt().coerceAtLeast(1), 0x33888888)
                        }
                    }, LinearLayout.LayoutParams((32 * dpx).toInt(), (32 * dpx).toInt()))
                    addView(TextView(this@MainActivity).apply {
                        text = p.label; textSize = 14f
                        setTextColor(getColor(if (sel) R.color.text else R.color.text2))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dpx).toInt() })
                    setOnClickListener { prefs.palette = p.id; renderPalettes(); preview.setColors(p) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dpx).toInt() })
            }
            paletteScroll.post { updatePaletteFade() } // the row's real width is known only after this layout pass
        }

        var loading = false // programmatic updates must not be written back
        fun load() {
            loading = true
            size.value = prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP).let { Math.round(it).toFloat() }
            alpha.value = (Math.round(prefs.bgAlpha * 20) * 5f).coerceIn(0f, 100f)
            width.value = (Math.round(prefs.widthFrac * 20) * 5f).coerceIn(40f, 100f)
            ja.isChecked = prefs.showJa
            stats.isChecked = prefs.showStats
            // only a real 0 is "auto": a small dragged height still shows as fixed (at least the first step)
            height.value = if (prefs.heightFrac <= 0f) 0f else (Math.round(prefs.heightFrac * 20) * 5f).coerceIn(5f, 40f)
            keepS.value = prefs.keepSentences.coerceIn(1, 6).toFloat()
            keepC.value = (Math.round(prefs.keepChars / 10f) * 10f).coerceIn(0f, 300f)
            loading = false
        }
        fun apply() {
            lSize.text = "文字大小  ${size.value.toInt()}"
            // the slider moves in 5% steps; until the user drags it, the label and preview keep the stored value
            // (the 78% default sits between two steps)
            val shownAlpha = prefs.bgAlpha // dragging saves first, so this is always what the overlay uses
            lAlpha.text = "背景浓淡  ${Math.round(shownAlpha * 100)}%"
            lWidth.text = "字幕宽度  ${width.value.toInt()}%"
            lHeight.text = if (prefs.heightFrac <= 0f) "字幕高度  自动（跟着内容变）" else "字幕高度  屏幕的 ${Math.round(prefs.heightFrac * 100)}%"
            lKeepS.text = "屏幕上至少留  ${keepS.value.toInt()} 句"
            lKeepC.text = "字数超过  ${keepC.value.toInt()} 字后，收起最早的句子"
            keepHint.text = "屏幕上会留着刚说完的几句话，方便回看；句数和字数都超过上面的数字时，最早的一句才会收起。字幕框放不下时，只显示最新的内容。"
            // the row that opens the size page says what is set there now
            findViewById<TextView>(R.id.sizeSummary).text = "宽 ${width.value.toInt()}% · 高 " +
                (if (prefs.heightFrac <= 0f) "自动" else "${Math.round(prefs.heightFrac * 100)}%") +
                " · 至少留 ${keepS.value.toInt()} 句 · ${keepC.value.toInt()} 字"
            preview.setColors(CaptionOverlayView.palette(prefs.palette))
            preview.applyStyle(size.value, shownAlpha)
            // same caption height as the overlay will get (the stage grows with it)
            preview.setMaxContentHeight((resources.displayMetrics.heightPixels * CaptionService.MAX_CAPTION_FRAC).toInt(), Int.MAX_VALUE)
            preview.setCaptionHeight((resources.displayMetrics.heightPixels * prefs.heightFrac).toInt())
            preview.layoutParams = FrameLayout.LayoutParams((findViewById<View>(R.id.previewStage).width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 0.8f).toInt()).let { (it * width.value / 100f).toInt() }, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)
            preview.setLines(
                listOf(CaptionOverlayView.Line("这一句已经说完，是正式字幕", if (ja.isChecked) "（这里显示原文）" else null, true),
                    CaptionOverlayView.Line("正在说的这一句…", if (ja.isChecked) "（原文）" else null, false)),
                if (stats.isChecked) "这里显示识别和翻译用了多久" else null,
            )
        }
        loadStyle = { load(); renderPalettes(); apply() }
        load(); renderPalettes(); apply()
        findViewById<View>(R.id.previewStage).post { apply() } // the stage's real width is known after layout
        size.addOnChangeListener { _, v, user -> if (user) { prefs.textSp = v; apply() } }
        alpha.addOnChangeListener { _, v, user -> if (user) { prefs.bgAlpha = v / 100f; apply() } }
        width.addOnChangeListener { _, v, user -> if (user) { prefs.widthFrac = v / 100f; apply() } }
        ja.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showJa = v; apply() } }
        stats.setOnCheckedChangeListener { _, v -> if (!loading) { prefs.showStats = v; apply() } }
        height.addOnChangeListener { _, v, user -> if (user) { prefs.heightFrac = v / 100f; apply() } }
        keepS.addOnChangeListener { _, v, user -> if (user) { prefs.keepSentences = v.toInt(); apply() } }
        keepC.addOnChangeListener { _, v, user -> if (user) { prefs.keepChars = v.toInt(); apply() } }
        findViewById<MaterialButton>(R.id.resetStyle).setOnClickListener {
            prefs.setStyle(13f, CaptionOverlayView.DEFAULT_ALPHA); prefs.widthFrac = Prefs.DEFAULT_WIDTH; prefs.showJa = true; prefs.showStats = true
            prefs.keepSentences = CaptionBoard.DEFAULT_SENTENCES; prefs.keepChars = CaptionBoard.DEFAULT_CHARS; prefs.heightFrac = 0f
            prefs.palette = CaptionOverlayView.PALETTES[0].id; renderPalettes()
            prefs.overlayX = -1; prefs.overlayY = -1; prefs.overlayBottom = -1
            load(); apply()
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------

    private fun start() {
        startRefused = false
        if (!prefs.byokReady) return // render() says what is missing
        // a connectivity test still running would probe the same service again alongside the session's own probe
        byokTest?.let { it.cancel(); byokTest = null; renderTranslate() }
        if (!hasAudio()) {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS // optional
            requestPermissions(ask.toTypedArray(), REQ_START)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        renderPermissions()
        render()
        if (requestCode == REQ_START && hasAudio()) start()
        else if (requestCode == REQ_START) status(R.color.err, "需要允许「听手机里播放的声音」", "只用来听 App 播放出来的声音，不会用麦克风。请再点一次开始，并选择「允许」。")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            // Seen on Android 15 with "a single app": the system refused the foreground service here
            // (mAllowStartForeground false). Show it instead of crashing; the exact trigger is not pinned down.
            try {
                CaptionService.start(this, resultCode, data)
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                startRefused = true; render()
                return
            }
            moveTaskToBack(true)
        }
    }

    private companion object {
        /** set by an activity a configuration change ends during a manual update check, read by its replacement */
        var manualCheckStopped = false
        const val REQ_START = 1      // runtime permission asked on the way to starting subtitles
        const val REQ_PROJECTION = 2
        val STYLE_KEYS = setOf("textSp", "bgAlpha", "widthFrac", "heightFrac", "showJa", "showStats", "keepSentences", "keepChars", "palette")
        const val REQ_PERM_ONLY = 3  // "去开启" in the permission list: grant only, never start
        const val LOGS_BEFORE_SCROLL = 5
        /** where the project lives (set by the author; the repository may not be public yet) */
        const val REPO_URL = "https://github.com/KorenKrita/simul-subtitle"
        /** Settings categories: the list shown for each, and its tab label. */
        val TABS: List<Pair<Int, String>> = listOf(R.id.tabCaption to "字幕", R.id.tabTranslate to "翻译", R.id.tabPage to "页面", R.id.tabRecords to "记录", R.id.tabAbout to "关于")
    }
}
