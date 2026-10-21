package io.github.korenkrita.simulsub

import io.github.korenkrita.simulsub.ServiceStatus.Light
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The subtitle card. Used twice: as the floating overlay (with [interactive] gestures) and as the live
 * preview in the settings screen, so what the user tunes is exactly what they get.
 *
 * Overlay gestures, unlocked: drag anywhere to move; a single tap shows the toolbar (font −/+, opacity −/+, mode, lock,
 * stop) together with a faint mark in each corner, and while they are shown a corner drag resizes width and height.
 * Locked: position and size are fixed and a tap only shows an unlock button in the bottom-right corner.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CaptionOverlayView(ctx: Context, private val interactive: Boolean) : LinearLayout(ctx) {
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val card = FrameLayout(ctx)
    /**
     * The caption text. Its scroll carries the smooth-scroll offset ([slidePx], px drawn lower) on top of the scroll
     * the TextView keeps by itself (the newest lines in view): the TextView resets its scroll before a frame is drawn,
     * and the offset must survive that.
     */
    private val text = object : TextView(ctx) {
        var slidePx = 0
        private var base = 0
        override fun scrollTo(x: Int, y: Int) { base = y; super.scrollTo(x, y - slidePx) }
        fun slide(px: Int) { slidePx = px; super.scrollTo(scrollX, base - px) }
    }
    private val stats = TextView(ctx)
    /** resize marks, one per corner: top-left, top-right, bottom-right, bottom-left */
    private val corners = List(4) { android.widget.ImageView(ctx) }
    private val unlockBtn = ImageButton(ctx)
    private val toolbar = LinearLayout(ctx)
    private val bg = GradientDrawable().apply { cornerRadius = dp(12f) }

    var textSp = 13f; private set
    var bgAlpha = DEFAULT_ALPHA; private set
    var colors = PALETTES[0]; private set
    private var lastLines: List<Line> = emptyList()
    private var lastStats: String? = null
    var locked = false; private set

    /** Overlay host callbacks (unused for the preview). */
    var onMove: (dx: Int, dy: Int) -> Unit = { _, _ -> }
    var onMoveEnd: () -> Unit = {}
    /** A corner dragged by dx/dy; [left]/[top] say which corner, so the opposite edges stay put. */
    var onResize: (dx: Int, dy: Int, left: Boolean, top: Boolean) -> Unit = { _, _, _, _ -> }
    var onStyleChanged: () -> Unit = {}
    var onStop: () -> Unit = {}
    /** Pause button tapped: the host flips the session's pause and calls [setPaused]. */
    var onTogglePause: () -> Unit = {}
    private var pauseBtn: android.widget.ImageButton? = null
    /** Shows ▶ while paused (tap to go on), ⏸ while running. */
    fun setPaused(p: Boolean) {
        pauseBtn?.apply {
            setImageResource(if (p) R.drawable.ic_play else R.drawable.ic_pause)
            contentDescription = if (p) "继续字幕" else "暂停字幕"
        }
    }
    /** Mode button tapped: the host flips "only finished sentences" in prefs and calls [setFinalOnly]. */
    var onToggleMode: () -> Unit = {}
    private val modeBtn = TextView(ctx).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(6f).toInt(), 0, dp(6f).toInt(), 0)
        minWidth = dp(36f).toInt()
        setOnClickListener { onToggleMode(); bumpToolbarTimer() }
    }
    /** Shows the current mode on the toolbar button; the label is what is on screen now. */
    fun setFinalOnly(v: Boolean) {
        modeBtn.text = if (v) "说完再出" else "边听边出"
        modeBtn.contentDescription = if (v) "现在每句说完才显示，点一下改为边听边出" else "现在边听边出，点一下改为每句说完再显示"
    }
    /** 原文 / 用时 tapped: the host flips the pref; [setShown] shows the new state. */
    var onToggleJa: () -> Unit = {}
    var onToggleStats: () -> Unit = {}
    private fun switchBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; minWidth = dp(36f).toInt()
        compoundDrawablePadding = dp(4f).toInt()
        setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
        setOnClickListener { onClick(); bumpToolbarTimer() }
    }
    private val jaBtn = switchBtn("原文") { onToggleJa() }
    private val statsBtn = switchBtn("用时") { onToggleStats() }
    /** A switch on the toolbar: a lit dot and white label when on, a hollow grey one when off. */
    private fun showSwitch(b: TextView, on: Boolean, what: String) {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setSize(dp(7f).toInt(), dp(7f).toInt())
            if (on) setColor(0xFFA5B4FC.toInt()) else { setColor(0); setStroke(dp(1f).toInt().coerceAtLeast(1), 0xFF8B8B94.toInt()) }
        }
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        b.setTextColor(if (on) Color.WHITE else 0xFF8B8B94.toInt())
        b.contentDescription = if (on) "${what}：开，点一下关掉" else "${what}：关，点一下打开"
    }
    /** The 原文 / 用时 switches show these states. */
    fun setShown(ja: Boolean, stats: Boolean) {
        showSwitch(jaBtn, ja, "显示原文"); showSwitch(statsBtn, stats, "显示识别和翻译用时")
    }
    private fun lightView() = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(0xFFDADCE0.toInt())
        gravity = Gravity.CENTER_VERTICAL; compoundDrawablePadding = dp(4f).toInt()
        setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val mtLight = lightView()
    private val jevLight = lightView()
    private fun showLight(t: TextView, name: String, l: Light, why: String?) {
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(dp(7f).toInt(), dp(7f).toInt()); setColor(l.color) }
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        // every state has a word (a light is never told by its colour alone); grey says "未开启" for a switched-off
        // service; the full reason of a fault is in the warning line
        val word = if (l == Light.GREY && why == "未开启") why else l.word
        t.text = "$name $word"
        t.contentDescription = "$name $word${why?.takeIf { it != word }?.let { "，$it" } ?: ""}"
    }
    /** The toolbar's service row: the translator and Jev, each with a light and, when not fine, why. */
    fun setServices(mt: Light, jev: Light, mtWhy: String?, jevWhy: String?) {
        showLight(mtLight, "翻译", mt, mtWhy); showLight(jevLight, "Jev", jev, jevWhy)
    }
    /** Toolbar opened/closed: the host widens the window while it is open so the toolbar is never clipped. */
    var onToolbarShown: (Boolean) -> Unit = {}

    /** Caption card width in px (the window may be wider while the toolbar is open). */
    fun setCardWidth(px: Int) {
        val lp = card.layoutParams
        if (lp.width != px) { lp.width = px; card.layoutParams = lp; stopSlide() }
    }
    /**
     * Places card and toolbar inside a window that covers both (toolbar open): px from the window's left edge. The
     * card stays where the user put it; only the toolbar shifts to fit the screen. [toolbarAbove] puts the toolbar
     * over the card when there is no room below it; [overlapPx] pulls it onto the card's edge when there is room on
     * neither side (it has the higher elevation, so it is drawn over the card).
     */
    fun placeCard(cardOffset: Int, toolbarOffset: Int, toolbarAbove: Boolean, overlapPx: Int) {
        val clp = card.layoutParams as LayoutParams
        if (clp.marginStart != cardOffset) { clp.marginStart = cardOffset; card.layoutParams = clp }
        val m = dp(TOOLBAR_GAP_DP).toInt() - overlapPx
        val tlp = toolbar.layoutParams as LayoutParams
        val top = if (toolbarAbove) 0 else m; val bottom = if (toolbarAbove) m else 0
        if ((indexOfChild(toolbar) == 0) != toolbarAbove) {
            removeView(toolbar)
            tlp.topMargin = top; tlp.bottomMargin = bottom; tlp.marginStart = toolbarOffset
            addView(toolbar, if (toolbarAbove) 0 else 1, tlp)
        } else if (tlp.marginStart != toolbarOffset || tlp.topMargin != top || tlp.bottomMargin != bottom) {
            tlp.topMargin = top; tlp.bottomMargin = bottom; tlp.marginStart = toolbarOffset; toolbar.layoutParams = tlp
        }
    }
    /** Caption card height in px at [widthPx], with the current text and caption height (measured, not laid out). */
    fun measureCardHeight(widthPx: Int): Int {
        card.measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return card.measuredHeight
    }
    /** Height the open toolbar adds to the window, gap included. */
    fun toolbarHeightPx(): Int {
        toolbar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return toolbar.measuredHeight + dp(TOOLBAR_GAP_DP).toInt()
    }

    /**
     * Caps caption and status heights so the toolbar keeps room on screen. Both are bottom-aligned, so when
     * they overflow (narrow card, big font) TextView itself keeps the newest lines in view, also after a
     * font or width change.
     */
    fun setMaxContentHeight(captionPx: Int, statusPx: Int) {
        if (stats.maxHeight != statusPx) stats.maxHeight = statusPx
        captionMaxPx = captionPx
        applyCaptionHeight()
    }

    private var captionMaxPx = Int.MAX_VALUE
    private var captionFixedPx = 0
    /**
     * Fixed caption area height in px (0 = fit the text, up to the max height). Text stays bottom-aligned, so
     * when it does not fit the newest lines are shown and older ones are cut off at the top.
     */
    fun setCaptionHeight(px: Int) { captionFixedPx = px; applyCaptionHeight() }
    private fun applyCaptionHeight() {
        val want = if (captionFixedPx > 0) captionFixedPx.coerceIn(minCaptionPx(), captionMaxPx.coerceAtLeast(minCaptionPx())) else 0
        if (want > 0) { if (text.minHeight != want) text.minHeight = want; if (text.maxHeight != want) text.maxHeight = want }
        else { if (text.minHeight != 0) text.minHeight = 0; if (text.maxHeight != captionMaxPx) text.maxHeight = captionMaxPx }
    }
    /** one caption line: the area never gets smaller than that */
    fun minCaptionPx() = text.lineHeight + text.paddingTop + text.paddingBottom
    /** Current caption area height (for starting a resize drag from what is on screen). */
    fun captionHeightPx() = text.height

    /** Natural width of the toolbar, independent of the current window width. */
    fun toolbarWidthPx(): Int {
        toolbar.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return toolbar.measuredWidth
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false

        text.apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setShadowLayer(dp(3f), 0f, dp(1f), 0xCC000000.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(2f), 1f)
        }
        stats.apply {
            setTextColor(0xFFA5B4FC.toInt())
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            visibility = GONE
        }
        val column = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(18f).toInt(), dp(10f).toInt(), dp(18f).toInt(), dp(10f).toInt())
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(stats, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        card.background = bg
        card.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // resize marks: a faint "›" in each corner, pointing out of the card, in the caption colour. Only drawn;
        // the card's touch listener finds the corner (see installGestures).
        val markRot = floatArrayOf(225f, 315f, 45f, 135f)
        val markGrav = intArrayOf(Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END, Gravity.BOTTOM or Gravity.END, Gravity.BOTTOM or Gravity.START)
        corners.forEachIndexed { k, c ->
            c.visibility = GONE
            c.setImageResource(R.drawable.ic_chevron_right); c.rotation = markRot[k]; c.alpha = 0.5f
            card.addView(c, FrameLayout.LayoutParams(dp(14f).toInt(), dp(14f).toInt(), markGrav[k]).apply {
                val m = dp(3f).toInt(); setMargins(m, m, m, m)
            })
        }
        // locked: a tap shows this in the bottom-right corner for a few seconds; tapping it unlocks
        unlockBtn.apply {
            setImageResource(R.drawable.ic_lock_open); contentDescription = "解锁位置和大小"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE626262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x33FFFFFF) }
            val p = dp(8f).toInt(); setPadding(p, p, p, p)
            visibility = GONE
            setOnClickListener { setLocked(false) }
        }
        card.addView(unlockBtn, FrameLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            val m = dp(3f).toInt(); setMargins(m, m, m, m)
        })
        // the card keeps its own place inside the window (see placeCard), and so does the toolbar
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START })

        buildToolbar()
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.START; topMargin = dp(TOOLBAR_GAP_DP).toInt() })
        if (interactive) installGestures()
        applyStyle(textSp, bgAlpha)
    }

    fun applyStyle(sizeSp: Float, alpha: Float) {
        if (sizeSp != textSp) stopSlide()
        textSp = sizeSp; bgAlpha = alpha
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        applyCaptionHeight() // a fixed height is clamped to one line, which depends on the font size
        bg.setColor(colors.bg and 0x00FFFFFF or ((alpha * 255).toInt().coerceIn(0, 255) shl 24))
        // with a fully transparent card the text keeps a stronger shadow so it stays readable on video;
        // dark text on a light card has none (a black shadow would only blur it)
        if (colors.shadow) text.setShadowLayer(dp(if (alpha < 0.2f) 4f else 3f), 0f, dp(1f), 0xEE000000.toInt())
        else text.setShadowLayer(0f, 0f, 0f, 0)
        // magpie's hairline edge on a dark card, so it stands off a dark video; none once the card is nearly clear
        bg.setStroke(dp(0.5f).toInt().coerceAtLeast(1), if (colors.shadow && alpha >= 0.2f) 0x1AFFFFFF else 0)
        corners.forEach { it.setColorFilter(colors.zh) }
    }

    /** Text / background colours of the captions; the background keeps the user's opacity. */
    class Palette(val id: String, val label: String, val zh: Int, val ja: Int, val draft: Int, val stats: Int, val bg: Int, val shadow: Boolean = true)

    fun setColors(p: Palette) {
        if (p === colors) return
        colors = p
        text.setTextColor(p.zh); stats.setTextColor(p.stats)
        applyStyle(textSp, bgAlpha)
        setLines(lastLines, lastStats) // spans carry the old colours
    }

    /** One entry per visible line: Chinese text, optional Japanese, draft flag. */
    class Line(val zh: String, val ja: String?, val isFinal: Boolean, /** the sentence (segment id); -1 = none */ val id: Long = -1)

    fun setLines(lines: List<Line>, statsLine: String?) {
        // smooth scroll (the overlay only, not the settings preview): where the newest sentence that stays on screen
        // is drawn now. Only once per frame: a second update before the next frame keeps the place it was drawn at.
        val keep = if (!interactive || slideAnchor >= 0 || !text.isLaidOut || !text.viewTreeObserver.isAlive) null
            else lastLines.lastOrNull { o -> o.isFinal && o.id >= 0 && lines.any { it.isFinal && it.id == o.id } }
        val from = keep?.let { anchorY(it.id) }
        lastLines = lines; lastStats = statsLine
        val starts = IntArray(lines.size)
        val sb = SpannableStringBuilder()
        val jaPx = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp * 0.62f, resources.displayMetrics)).toInt()
        for ((i, l) in lines.withIndex()) {
            if (sb.isNotEmpty()) sb.append('\n')
            if (!l.ja.isNullOrEmpty()) {
                val s = sb.length
                sb.append(l.ja).append('\n')
                sb.setSpan(AbsoluteSizeSpan(jaPx), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(colors.ja), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val s = sb.length
            starts[i] = s
            // the draft line is visibly separate from the finished sentences above: dimmer, smaller, marked "…"
            if (!l.isFinal) sb.append("… ")
            sb.append(l.zh)
            if (!l.isFinal) {
                sb.setSpan(ForegroundColorSpan(colors.draft), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.85f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        text.text = sb
        lineStarts = starts
        stats.text = statsLine ?: ""
        stats.visibility = if (statsLine.isNullOrEmpty()) GONE else VISIBLE
        if (keep != null && from != null) {
            slideAnchor = keep.id; slideFrom = from + slideOffset; slideW = text.width; slideSize = text.textSize
            text.viewTreeObserver.addOnPreDrawListener(slideStart) // after setText: runs after the TextView's own
        }
    }

    private var lineStarts = IntArray(0)
    private var slideAnchor = -1L
    private var slideFrom = 0f
    private var slideW = 0
    private var slideSize = 0f
    /** px the text is drawn lower than laid out */
    private var slideOffset = 0f
    private var slide: ValueAnimator? = null

    /** Screen y of the top of final [id]'s Chinese line as laid out now, without the slide; null if not shown. */
    private fun anchorY(id: Long): Float? {
        val i = lastLines.indexOfFirst { it.isFinal && it.id == id }
        val l = text.layout
        if (i < 0 || i >= lineStarts.size || l == null) return null
        val loc = IntArray(2); text.getLocationOnScreen(loc)
        // the lines sit at the bottom of the text area (also when the oldest are cut off): measure from there
        return loc[1] + text.height - text.extendedPaddingBottom + l.getLineTop(l.getLineForOffset(lineStarts[i])) - l.height.toFloat()
    }

    /**
     * Right before the next frame (new text laid out, window moved): the text is drawn where it was and slides to its
     * new place. Measured on screen, so a card that grows downward, where nothing above moves, does not slide.
     */
    private val slideStart = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            text.viewTreeObserver.removeOnPreDrawListener(this)
            val id = slideAnchor
            slideAnchor = -1
            // a new width or font size rewraps everything: no slide
            val to = anchorY(id)?.takeIf { text.width == slideW && text.textSize == slideSize }
            if (to == null) { stopSlide(); return true }
            val d = slideFrom - to
            if (abs(d - slideOffset) < 1f) { setSlide(slideOffset); return true } // nothing moved: a slide runs on
            slide?.cancel()
            if (abs(d) < 1f) { setSlide(0f); return true }
            setSlide(d)
            slide = ValueAnimator.ofFloat(d, 0f).apply {
                duration = SLIDE_MS; interpolator = DecelerateInterpolator()
                addUpdateListener { setSlide(it.animatedValue as Float) }
                start()
            }
            return true
        }
    }

    /** Text drawn [off] px lower, still clipped to the text area; lines scrolled out of a full area slide out too. */
    private fun setSlide(off: Float) {
        slideOffset = off
        text.slide(off.toInt())
    }

    /** Width or font size changed: the text rewraps, a running slide would be off; it ends where it is laid out. */
    private fun stopSlide() {
        slide?.cancel(); slide = null
        if (slideOffset != 0f) { slideOffset = 0f; text.post { setSlide(0f) } }
    }

    /**
     * Two rows, both as wide as the wider one, every cell sharing that width (no gaps):
     *   字号  A−  A+   │  背景  −  +
     *   边听边出 │ ● 原文 │ ● 用时 │ 🔒 │ ✕
     *   ● 翻译 │ ● Jev 受限     (service lights, read only; the reason is in the warning line)
     * The two switches carry a dot that is lit when on, so they read as switches. As small as a finger allows.
     */
    private fun buildToolbar() {
        toolbar.apply {
            orientation = VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0xF526262B.toInt()); setStroke(dp(0.5f).toInt().coerceAtLeast(1), 0x1AFFFFFF) }
            setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            elevation = dp(6f)
            // the hairline between the rows: a divider, not a MATCH_PARENT child, which would stretch the toolbar
            // to the whole window (as wide as the caption card) instead of its buttons
            dividerDrawable = GradientDrawable().apply { setColor(0x1AFFFFFF); setSize(0, dp(0.5f).toInt().coerceAtLeast(1)) }
            showDividers = SHOW_DIVIDER_MIDDLE
            visibility = GONE
        }
        val size = dp(36f).toInt() // smallest comfortable tap target
        // a cell takes its share of the row (weight); its natural size is the minimum
        fun cell(weight: Float = 1f) = LayoutParams(LayoutParams.WRAP_CONTENT, size, weight)
        fun btn(icon: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
            setImageResource(icon); contentDescription = label
            background = null; val p = dp(9f).toInt(); setPadding(p, p, p, p)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            minimumWidth = size
            layoutParams = cell()
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun tag(t: String) = TextView(context).apply {
            text = t; setTextColor(0xFF8B8B94.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER; setPadding(dp(6f).toInt(), 0, dp(2f).toInt(), 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size)
        }
        fun sep() = View(context).apply { setBackgroundColor(0x1AFFFFFF); layoutParams = LayoutParams(dp(1f).toInt(), dp(16f).toInt()).apply { marginStart = dp(2f).toInt(); marginEnd = dp(2f).toInt() } }
        fun tbtn(label: String, desc: String, onClick: () -> Unit) = TextView(context).apply {
            text = label; contentDescription = desc
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; minWidth = size
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
            layoutParams = cell()
            setOnClickListener { onClick(); bumpToolbarTimer() }
        }
        fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val full = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        // two halves, each a label with its −/+ kept together (the spare width goes around a group, not inside it)
        fun group(label: String, minus: View, plus: View) = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            addView(tag(label)); addView(minus.apply { layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size) })
            addView(plus.apply { layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, size) })
        }
        toolbar.addView(row().apply {
            addView(group("字号", tbtn("A−", "字号减小") { setSize(textSp - 2) }, tbtn("A+", "字号增大") { setSize(textSp + 2) }), cell())
            addView(sep())
            addView(group("背景", tbtn("−", "背景更透明") { setAlphaStep(bgAlpha - 0.15f) }, tbtn("+", "背景更不透明") { setAlphaStep(bgAlpha + 0.15f) }), cell())
        }, full)
        toolbar.addView(row().apply {
            modeBtn.layoutParams = cell(1.4f); addView(modeBtn)
            addView(sep())
            jaBtn.layoutParams = cell(); addView(jaBtn)
            statsBtn.layoutParams = cell(); addView(statsBtn)
            addView(sep())
            addView(btn(R.drawable.ic_pause, "暂停字幕") { onTogglePause() }.also { pauseBtn = it })
            addView(btn(R.drawable.ic_lock, "锁定位置和大小") { toggleLock() })
            addView(btn(R.drawable.ic_close, "停止字幕") { onStop() }.apply { setColorFilter(0xFFF28B82.toInt()) })
        }, full)
        // read only: each half shares the width; short words only, so the row never widens the toolbar
        toolbar.addView(row().apply {
            addView(mtLight, LayoutParams(0, dp(28f).toInt(), 1f))
            addView(sep())
            addView(jevLight, LayoutParams(0, dp(28f).toInt(), 1f))
        }, full)
        setServices(Light.GREY, Light.GREY, null, null)
    }

    private fun setSize(v: Float) { applyStyle(v.coerceIn(MIN_SP, MAX_SP), bgAlpha); onStyleChanged() }
    private fun setAlphaStep(v: Float) { applyStyle(textSp, v.coerceIn(0f, 1f)); onStyleChanged() }

    fun setLocked(v: Boolean) {
        locked = v
        hideUnlock()
        showCorners()
    }
    private fun toggleLock() { setLocked(!locked); if (locked) hideToolbar() }
    /** the resize marks go with the toolbar: shown (and draggable) only while it is open and the card is unlocked */
    private fun showCorners() {
        val on = interactive && !locked && toolbar.visibility == VISIBLE
        corners.forEach { it.visibility = if (on) VISIBLE else GONE }
    }

    private val hideRunnable = Runnable { hideToolbar() }
    private fun bumpToolbarTimer() { removeCallbacks(hideRunnable); postDelayed(hideRunnable, 4_000) }
    private fun showToolbar() {
        toolbar.visibility = VISIBLE
        showCorners()
        bumpToolbarTimer()
        onToolbarShown(true)
    }
    private fun hideToolbar() {
        if (toolbar.visibility == GONE) return
        toolbar.visibility = GONE
        showCorners()
        onToolbarShown(false)
    }
    private val hideUnlockRunnable = Runnable { unlockBtn.visibility = GONE }
    private fun hideUnlock() { removeCallbacks(hideUnlockRunnable); unlockBtn.visibility = GONE }
    private fun showUnlock() { unlockBtn.visibility = VISIBLE; removeCallbacks(hideUnlockRunnable); postDelayed(hideUnlockRunnable, 3_000) }

    /** A tap on the card: the toolbar when unlocked; when locked only the unlock button. */
    private fun tap() {
        if (locked) { if (unlockBtn.visibility == VISIBLE) hideUnlock() else showUnlock(); return }
        if (toolbar.visibility == VISIBLE) hideToolbar() else showToolbar()
    }

    // the toolbar stays while a finger is down anywhere on the overlay (card or toolbar button), and its timer
    // restarts when the finger lifts
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (interactive) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> removeCallbacks(hideRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (toolbar.visibility == VISIBLE) bumpToolbarTimer()
        }
        return super.dispatchTouchEvent(e)
    }
    private fun installGestures() {
        val slop = dp(8f)
        val grab = dp(28f)
        var downX = 0f; var downY = 0f; var lastX = 0f; var lastY = 0f; var moved = false
        // the corner under the finger at ACTION_DOWN (-1: none); only while the toolbar (and so the marks) is shown.
        // One listener on the card picks it, so the four grab areas never overlap: each gets at most half the card.
        var corner = -1
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; moved = false
                    val gx = minOf(grab, card.width / 2f); val gy = minOf(grab, card.height / 2f)
                    val l = e.x < gx; val r = e.x > card.width - gx; val t = e.y < gy; val b = e.y > card.height - gy
                    corner = if (locked || toolbar.visibility != VISIBLE) -1 else when { t && l -> 0; t && r -> 1; b && r -> 2; b && l -> 3; else -> -1 }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                    if (moved && !locked) {
                        // whole pixels only: keep the fraction for the next move, or slow drags would not move at all
                        val dx = (e.rawX - lastX).toInt(); val dy = (e.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            if (corner >= 0) onResize(dx, dy, corner == 0 || corner == 3, corner <= 1) else onMove(dx, dy)
                            lastX += dx; lastY += dy
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) tap() else if (!locked) onMoveEnd()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved && !locked) onMoveEnd()
                }
            }
            true
        }
    }


    companion object {
        /** Caption size range, shared with the settings slider. */
        const val MIN_SP = 5f
        const val MAX_SP = 36f
        /** Card opacity when the user has not set one: magpie's ink card at 78%. */
        const val DEFAULT_ALPHA = 0.78f
        /** Space between the caption card and the toolbar. */
        const val TOOLBAR_GAP_DP = 6f
        /** how long new lines take to slide into place */
        const val SLIDE_MS = 200L

        /** Caption colour schemes; the first is the default. ids are stored in prefs, keep them stable. */
        val PALETTES = listOf(
            // magpie's ink card (#1a1a1e) with its quiet greys and indigo; the default since 0.6
            Palette("ink", "墨色", 0xFFFFFFFF.toInt(), 0xCCDCDCE4.toInt(), 0xFFB9B9C1.toInt(), 0xFFA5B4FC.toInt(), 0xFF1A1A1E.toInt()),
            Palette("white", "经典白", 0xFFFFFFFF.toInt(), 0xCCDCD8F0.toInt(), 0xFFB9B5C9.toInt(), 0xFFB4ACFA.toInt(), 0xFF100E1A.toInt()),
            Palette("yellow", "影院黄", 0xFFFFE066.toInt(), 0xCCFFF3C4.toInt(), 0xFFCFC48F.toInt(), 0xFFFFC94D.toInt(), 0xFF000000.toInt()),
            Palette("mint", "薄荷绿", 0xFFA7F3D0.toInt(), 0xCCD1FAE5.toInt(), 0xFF8CBBA6.toInt(), 0xFF6EE7B7.toInt(), 0xFF0B1F1A.toInt()),
            Palette("sky", "天空蓝", 0xFFBAE6FD.toInt(), 0xCCE0F2FE.toInt(), 0xFF93B4C9.toInt(), 0xFF7DD3FC.toInt(), 0xFF0B1726.toInt()),
            Palette("sakura", "樱花粉", 0xFFFBCFE8.toInt(), 0xCCFCE7F3.toInt(), 0xFFC9A3B8.toInt(), 0xFFF9A8D4.toInt(), 0xFF24101C.toInt()),
            Palette("paper", "白底黑字", 0xFF16151C.toInt(), 0xCC3D3B48.toInt(), 0xFF6E6C7A.toInt(), 0xFF5B4BD6.toInt(), 0xFFFFFFFF.toInt(), shadow = false),
        )
        fun palette(id: String?) = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]
    }
}
