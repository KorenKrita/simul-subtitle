package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import kotlin.math.abs
import kotlin.math.roundToInt

/** Text / background colours of the captions (ARGB); the background keeps the user's opacity. */
class Palette(val id: String, val label: String, val zh: Long, val ja: Long, val draft: Long, val stats: Long, val bg: Long, val shadow: Boolean = true)

/** The phone's caption colour schemes; the first is the default. ids are stored in settings, keep them stable. */
val PALETTES = listOf(
    Palette("ink", "墨色", 0xFFFFFFFF, 0xCCDCDCE4, 0xFFB9B9C1, 0xFFA5B4FC, 0xFF1A1A1E),
    Palette("white", "经典白", 0xFFFFFFFF, 0xCCDCD8F0, 0xFFB9B5C9, 0xFFB4ACFA, 0xFF100E1A),
    Palette("yellow", "影院黄", 0xFFFFE066, 0xCCFFF3C4, 0xFFCFC48F, 0xFFFFC94D, 0xFF000000),
    Palette("mint", "薄荷绿", 0xFFA7F3D0, 0xCCD1FAE5, 0xFF8CBBA6, 0xFF6EE7B7, 0xFF0B1F1A),
    Palette("sky", "天空蓝", 0xFFBAE6FD, 0xCCE0F2FE, 0xFF93B4C9, 0xFF7DD3FC, 0xFF0B1726),
    Palette("sakura", "樱花粉", 0xFFFBCFE8, 0xCCFCE7F3, 0xFFC9A3B8, 0xFFF9A8D4, 0xFF24101C),
    Palette("paper", "白底黑字", 0xFF16151C, 0xCC3D3B48, 0xFF6E6C7A, 0xFF5B4BD6, 0xFFFFFFFF, shadow = false),
)
fun palette(id: String?) = PALETTES.firstOrNull { it.id == id } ?: PALETTES[0]

const val MIN_SP = 5f
const val MAX_SP = 36f
/** The phone's sp → points on the desktop: the same number reads about as large at a desk as on the phone in the hand. */
private const val SP_SCALE = 1.6f

/**
 * A press on the card: a click (no movement) or a drag of the pointer on the screen, the way the phone's view reads
 * raw touch coordinates. Deltas come from the pointer's screen position, never from its position inside the window:
 * the window moving under the pointer would otherwise cancel the next delta and the card would shake.
 * [onDrag] gets the move since the last event, in screen points.
 */
private suspend fun PointerInputScope.pressOrDrag(onClick: () -> Unit, onStart: () -> Unit,
                                                  onDrag: (Float, Float) -> Unit, onEnd: () -> Unit) {
    fun pointer(): Pair<Float, Float> { val l = java.awt.MouseInfo.getPointerInfo().location; return l.x.toFloat() to l.y.toFloat() }
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            if (down.type != PointerEventType.Press || down.changes.any { it.isConsumed }) continue
            val (px, py) = pointer()
            var lastX = px; var lastY = py
            var total = 0f
            var moved = false
            var mine = true
            while (mine) {
                val e = awaitPointerEvent()
                // the corner marks are children of the card: whoever takes the press owns the whole gesture, or a
                // resize would drag the card as well
                if (e.changes.any { it.isConsumed }) { mine = false; break }
                e.changes.forEach { it.consume() }
                if (e.type == PointerEventType.Release) break
                val (x1, y1) = pointer()
                val dx = x1 - lastX; val dy = y1 - lastY
                total += Math.abs(dx) + Math.abs(dy)
                if (!moved && total > 4f) { moved = true; onStart() }
                if (moved) { onDrag(dx, dy); lastX = x1; lastY = y1 }
            }
            if (moved) onEnd() else if (mine) onClick()
        }
    }
}

/**
 * The captions: what [OverlayState] holds, in the look the settings choose (also the settings screen's preview,
 * with [interactive] false).
 */
@Composable
fun CaptionCard(
    o: OverlayState, prefs: Prefs, modifier: Modifier = Modifier, fixedHeight: Float = 0f, maxHeight: Float = Float.MAX_VALUE,
    slides: Boolean = false, keepsBottom: Boolean = false,
    corners: (@Composable androidx.compose.foundation.layout.BoxScope.(Color) -> Unit)? = null,
) {
    val p = palette(prefs.palette)
    val size = prefs.textSp.coerceIn(MIN_SP, MAX_SP) * SP_SCALE
    val alpha = prefs.bgAlpha
    val bg = Color(p.bg).copy(alpha = alpha)
    // with a nearly clear card the text keeps a stronger shadow so it stays readable on video; dark text has none
    val shadow = if (p.shadow) Shadow(Color(0xEE000000), androidx.compose.ui.geometry.Offset(0f, 1.5f), if (alpha < 0.2f) 6f else 4.5f) else null
    val lines by o.lines
    val diag by o.diag
    val starts = IntArray(lines.size)
    val text = buildAnnotatedString {
        lines.forEachIndexed { i, l ->
            if (i > 0) append('\n')
            if (!l.ja.isNullOrEmpty()) withStyle(SpanStyle(color = Color(p.ja), fontSize = (size * 0.62f).sp)) { append(l.ja); append('\n') }
            starts[i] = length
            // the draft line is visibly separate from the finished sentences above: dimmer, smaller, marked "…"
            if (l.isFinal) append(l.zh)
            else withStyle(SpanStyle(color = Color(p.draft), fontSize = (size * 0.85f).sp)) { append("… "); append(l.zh) }
        }
    }
    // Smooth scroll ([slides]): new text is drawn first where the newest sentence that stays on screen was, then
    // slides to its new place. Measured from the card's edge that stays put ([keepsBottom]: the bottom, else the
    // top), so a drag, which moves both, slides nothing, and where the card grows downward nothing above moves.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var slide by remember { mutableFloatStateOf(0f) }
    val last = remember { object { var shown: Shown? = null; var job: Job? = null; var onLayout: (TextLayoutResult) -> Unit = {} } }
    var diagH by remember { mutableIntStateOf(0) }
    val diagPx = if (diag == null) 0 else diagH
    last.onLayout = onLayout@{ r ->
        val boxPx = if (fixedHeight > 0f) fixedHeight * density else minOf(r.size.height.toFloat(), maxHeight * density)
        // from that edge to the bottom of the text area, where the lines sit (also when the oldest are cut off);
        // 10 = the card's padding
        val edgePx = if (keepsBottom) -10f * density - diagPx else 10f * density + boxPx
        val p = last.shown
        val now = Shown(lines, starts, r, keepsBottom, edgePx, size).also { last.shown = it }
        if (!slides) return@onLayout
        // a new width or font size rewraps everything, a new edge measures from elsewhere: no slide
        if (p == null || p.layout.size.width != r.size.width || p.size != size || p.keepsBottom != keepsBottom) {
            last.job?.cancel(); slide = 0f; return@onLayout
        }
        val keep = p.lines.lastOrNull { o -> o.isFinal && o.id >= 0 && lines.any { it.isFinal && it.id == o.id } } ?: return@onLayout
        val d = p.y(keep.id) + slide - now.y(keep.id)
        if (abs(d - slide) < 1f) return@onLayout // nothing moved: a running slide goes on
        last.job?.cancel()
        if (abs(d) < 1f) { slide = 0f; return@onLayout }
        slide = d
        last.job = scope.launch { animate(d, 0f, animationSpec = tween(SLIDE_MS)) { v, _ -> slide = v } }
    }
    // one callback for good: a new one on every recomposition (a drag recomposes) would lay the text out again
    val onTextLayout = remember { { r: TextLayoutResult -> last.onLayout(r) } }
    // magpie's hairline edge on a dark card, so it stands off a dark video; none once the card is nearly clear
    val edge = if (p.shadow && alpha >= 0.2f) Color(0x1AFFFFFF) else Color.Transparent
    Box(modifier.background(bg, RoundedCornerShape(12.dp)).border(0.5.dp, edge, RoundedCornerShape(12.dp))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            // newest at the bottom; what does not fit leaves at the top
            // fitting the text, up to [maxHeight]: a long caption never grows past its display
            val area = if (fixedHeight > 0f) Modifier.height(fixedHeight.dp) else Modifier.heightIn(max = maxHeight.dp)
            if (lines.isNotEmpty() || fixedHeight > 0f) Box(Modifier.fillMaxWidth().then(area).clipToBounds().wrapContentHeight(Alignment.Bottom, unbounded = true)) {
                Text(text, color = Color(p.zh), fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().graphicsLayer { translationY = slide },
                    style = androidx.compose.ui.text.TextStyle(shadow = shadow), onTextLayout = onTextLayout)
            }
            diag?.let { Text(it, color = Color(p.stats), fontSize = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().onSizeChanged { s -> diagH = s.height }, maxLines = 3) }
        }
        corners?.invoke(this, Color(p.zh))
    }
}

/** Caption text as laid out: what it shows, and from which card edge, how far, the bottom of its area was (px). */
private class Shown(val lines: List<CaptionLine>, val starts: IntArray, val layout: TextLayoutResult,
                    val keepsBottom: Boolean, val edgePx: Float, val size: Float) {
    /** y (px) of the top of final [id]'s Chinese line, from that edge */
    fun y(id: Long) = edgePx + layout.getLineTop(layout.getLineForOffset(starts[lines.indexOfFirst { it.isFinal && it.id == id }])) - layout.size.height
}

/** how long new lines take to slide into place */
private const val SLIDE_MS = 200

/** The toolbar under (or over) the card: 字号 / 背景, 边听边出 · 原文 · 用时 · 暂停 · 锁定 · ✕, and the two service lights. */
@Composable
private fun Toolbar(o: OverlayState, prefs: Prefs, onLock: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    val dim = Color(0xFF8B8B94)
    @Composable fun tag(t: String) = Text(t, color = dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, end = 2.dp))
    @Composable fun btn(t: String, color: Color = Color.White, onClick: () -> Unit) =
        Box(Modifier.height(36.dp).widthIn(min = 36.dp).clickableNoRipple(onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(t, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    @Composable fun sep() = Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(16.dp).background(Color(0x1AFFFFFF)))
    // a switch: a lit dot and white label when on, a hollow grey one when off
    @Composable fun sw(t: String, on: Boolean, onClick: () -> Unit) =
        Row(Modifier.height(36.dp).clickableNoRipple(onClick).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).then(if (on) Modifier.background(Color(0xFFA5B4FC), CircleShape)
                else Modifier.border(1.dp, dim, CircleShape)))
            Spacer(Modifier.width(5.dp))
            Text(t, color = if (on) Color.White else dim, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    @Composable fun light(name: String, l: ServiceStatus.Light, why: String?) =
        Row(Modifier.height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(Color(l.color.toLong() and 0xFFFFFFFFL), CircleShape))
            Spacer(Modifier.width(5.dp))
            // every state has a word (a light is never told by its colour alone)
            val word = if (l == ServiceStatus.Light.GREY && why == "未开启") why else l.word
            Text("$name $word", color = Color(0xFFDADCE0), fontSize = 12.sp, maxLines = 1)
        }
    Column(modifier.background(Color(0xF526262B), RoundedCornerShape(10.dp)).border(0.5.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
        .padding(horizontal = 4.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tag("字号"); btn("A−") { prefs.textSp = (prefs.textSp - 2).coerceIn(MIN_SP, MAX_SP) }
            btn("A+") { prefs.textSp = (prefs.textSp + 2).coerceIn(MIN_SP, MAX_SP) }
            sep()
            tag("背景"); btn("−") { prefs.bgAlpha = (prefs.bgAlpha - 0.15f).coerceIn(0f, 1f) }
            btn("+") { prefs.bgAlpha = (prefs.bgAlpha + 0.15f).coerceIn(0f, 1f) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            btn(if (prefs.finalOnly) "说完再出" else "边听边出") { prefs.finalOnly = !prefs.finalOnly }
            sep()
            sw("原文", prefs.showJa) { prefs.showJa = !prefs.showJa }
            sw("用时", prefs.showStats) { prefs.showStats = !prefs.showStats }
            sep()
            if (!CaptionService.endedState.value) btn(if (CaptionService.pausedState.value) "继续" else "暂停") { CaptionService.togglePause() }
            btn("锁定", onClick = onLock)
            btn("✕", Color(0xFFF28B82), onStop)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            light("翻译", o.mtLight.value, o.mtReason.value); sep(); light("Jev", o.jevLight.value, o.jevReason.value)
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.then(
    Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, onClick = onClick))

/**
 * The always-on-top caption window of the running session: transparent and frameless. Drag the card to move it;
 * a click opens the toolbar, whose corner marks resize the card. Place and size are the settings' (card top-left
 * in points, width / height as parts of the screen), saved when a drag ends.
 */
@Composable
fun OverlayWindow(o: OverlayState, prefs: Prefs, onStop: () -> Unit) {
    fun primary() = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    // every display, in the space window positions live in: the card may sit on any of them
    fun displays() = GraphicsEnvironment.getLocalGraphicsEnvironment().let { g ->
        g.screenDevices.map { it.defaultConfiguration.bounds }.ifEmpty { listOf(g.maximumWindowBounds) }
    }
    var screen by remember { mutableStateOf(primary()) }
    var screens by remember { mutableStateOf(displays()) }
    fun screenAt(px: Float, py: Float) = screens.firstOrNull { it.contains(px.roundToInt(), py.roundToInt()) } ?: screens.first()
    fun widthOf(frac: Float) = screen.width * frac.coerceIn(Prefs.MIN_WIDTH, 1f)
    var cardW by remember { mutableStateOf(widthOf(prefs.widthFrac)) }
    var fixedH by remember { mutableStateOf(screen.height * prefs.heightFrac) }
    /** true when a card of [w] x [h] at [nx], [ny] has a display under some part of it */
    fun onSome(nx: Float, ny: Float, w: Float, h: Float) =
        screens.any { it.intersects(java.awt.Rectangle(nx.roundToInt(), ny.roundToInt(), w.roundToInt(), h.roundToInt())) }
    // the saved spot, on whichever display it was (also left of or above the main one); one on no display any more
    // (that display is gone) opens at the default place
    var x by remember { mutableStateOf(screen.x + (screen.width - cardW) / 2) }
    var y by remember { mutableStateOf(screen.y + screen.height * 2f / 3) }
    // Once placed, a card that fits its text and sits in the lower half of its display keeps this bottom edge: new
    // text pushes the lines above it up and the line being read stays put. Higher up (null) it keeps its top edge.
    var bottomEdge by remember { mutableStateOf<Float?>(null) }
    var anchored by remember { mutableStateOf(false) } // the edge to keep was chosen for this placement
    remember {
        val sx = prefs.overlayX; val sy = prefs.overlayY
        if (sx != null && sy != null && onSome(sx.toFloat(), sy.toFloat(), cardW, 40f)) {
            x = sx.toFloat(); y = sy.toFloat()
            // saved in the lower half: back with its bottom edge where it was, whatever its text is now
            prefs.overlayBottom?.let { sb ->
                val s = screenAt(x + cardW / 2, y); anchored = true
                if (prefs.heightFrac <= 0f && (sy + sb) / 2f > s.y + s.height / 2f) bottomEdge = sb.toFloat()
            }
        }
    }
    var toolbar by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var unlock by remember { mutableStateOf(false) }
    var poke by remember { mutableIntStateOf(0) } // restarts the toolbar's hide timer
    var cardH by remember { mutableIntStateOf(0) }
    /** a placement ended: choose the edge the card keeps from where it is now */
    fun anchor() {
        if (cardH <= 0) return
        val s = screenAt(x + cardW / 2, y); anchored = true
        bottomEdge = if (fixedH <= 0f && y + cardH / 2f > s.y + s.height / 2f) y + cardH else null
    }
    // the card's top: from its bottom edge as soon as its new height is known, so the window moves with the text
    // (a function: gesture callbacks outlive this composition and must read the state as it is then)
    // (kept on the display of that edge: a saved or old edge may lie below it now)
    fun cardTop() = bottomEdge?.let { b ->
        if (cardH <= 0) null else screenAt(x + cardW / 2, b - 1).let { s -> (minOf(b, (s.y + s.height).toFloat()) - cardH).coerceAtLeast(s.y.toFloat()) }
    } ?: y
    val top = cardTop()
    /**
     * A card with no display under it never gets another frame: its redrawer then waits for a vsync that never comes
     * and the whole app freezes. Moving between displays is free; only a spot on no screen at all snaps back.
     */
    fun place(nx: Float, ny: Float): Pair<Float, Float> {
        val w = cardW; val h = (if (fixedH > 0f) fixedH else if (cardH > 0) cardH.toFloat() else 40f)
        if (onSome(nx, ny, w, h))
            return nx.coerceIn(screens.minOf { it.x }.toFloat(), (screens.maxOf { it.x + it.width }.toFloat() - w).coerceAtLeast(screens.minOf { it.x }.toFloat())) to
                   ny.coerceIn(screens.minOf { it.y }.toFloat(), (screens.maxOf { it.y + it.height }.toFloat() - h).coerceAtLeast(screens.minOf { it.y }.toFloat()))
        val s = screenAt(x, y)
        return nx.coerceIn(s.x.toFloat(), (s.x + s.width - w).coerceAtLeast(s.x.toFloat())) to
               ny.coerceIn(s.y.toFloat(), (s.y + s.height - h).coerceAtLeast(s.y.toFloat()))
    }
    var barW by remember { mutableIntStateOf(0) }
    var barH by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(toolbar, poke, dragging) { if (toolbar && !dragging) { delay(4_000); toolbar = false } }
    LaunchedEffect(unlock) { if (unlock) { delay(3_000); unlock = false } }
    // the settings screen moves the sliders: follow them (a drag here writes the same values back)
    LaunchedEffect(prefs.widthFrac) { cardW = widthOf(prefs.widthFrac) }
    // displays come and go (plugged in, rearranged, resolution changed): follow them, and a card left on a display
    // that is gone moves back onto one before its window is moved or resized again
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            val now = displays()
            if (now != screens) {
                screens = now; screen = primary()
                if (!onSome(x, y, cardW, maxOf(cardH, 40).toFloat())) {
                    val s = screens.first()
                    x = s.x + (s.width - cardW).coerceAtLeast(0f) / 2; y = s.y + s.height * 2f / 3
                    bottomEdge = null; anchored = false
                }
            }
        }
    }
    // a card that grows (longer text, one more sentence, the original shown) keeps its newest line on its display:
    // it moves up, never below the bottom edge. A drag places it itself.
    LaunchedEffect(cardH, fixedH, screens, bottomEdge) {
        if (dragging || cardH <= 0) return@LaunchedEffect
        if (!anchored) anchor()
        if (bottomEdge != null) { y = top; return@LaunchedEffect }
        val s = screenAt(x + cardW / 2, y)
        val h = if (fixedH > 0f) fixedH else cardH.toFloat()
        if (y + h > s.y + s.height) y = maxOf(s.y.toFloat(), s.y + s.height - h)
    }
    val gap = 6
    val bar = toolbar && barH > 0
    val winW = maxOf(cardW, if (toolbar) barW.toFloat() else 0f)
    // the toolbar is centred on the card, below it, or above it when there is no room below
    val here = screenAt(x, top)
    val above = bar && top + cardH + gap + barH > here.y + here.height
    val state = rememberWindowState(position = WindowPosition(x.dp, y.dp), size = DpSize(cardW.dp, 200.dp))
    LaunchedEffect(x, top, winW, cardW, bar, above, cardH, barH) {
        state.position = WindowPosition((x - (winW - cardW) / 2).dp, (if (above) top - gap - barH else top).dp)
        // room for the toolbar to be measured once before it is placed
        state.size = DpSize(winW.dp, (maxOf(cardH, 40) + if (toolbar) gap + maxOf(barH, 140) else 0).dp)
    }
    fun save() = prefs.setOverlay(x.roundToInt(), y.roundToInt(), if (cardH > 0) (y + cardH).roundToInt() else null,
        (cardW / screen.width).coerceIn(Prefs.MIN_WIDTH, 1f), if (fixedH > 0) (fixedH / screen.height).coerceIn(0.05f, 0.4f) else 0f)
    LaunchedEffect(prefs.heightFrac) {
        if (fixedH == screen.height * prefs.heightFrac) return@LaunchedEffect
        fixedH = screen.height * prefs.heightFrac
        // the edge to keep is chosen from the card at its new height: measured a frame later
        withFrameNanos {}; withFrameNanos {}
        anchor(); save()
    }
    Window(onCloseRequest = onStop, state = state, title = "同传字幕 · 字幕", undecorated = true, transparent = true,
        alwaysOnTop = true, resizable = false, focusable = false) {
        // this window's own display scale: sizes are measured in its pixels (another display may scale differently)
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        if (!isWindows) LaunchedEffect(Unit) { window.rootPane.putClientProperty("apple.awt.windowShadow", false) }
        val toolbarView = @Composable {
            Toolbar(o, prefs, onLock = { locked = true; toolbar = false }, onStop = onStop,
                modifier = Modifier.wrapContentSize().onSizeChanged { barW = (it.width / density).roundToInt(); barH = (it.height / density).roundToInt() }
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(); poke++ } } })
        }
        // measured at its natural height, not the window's: the window then follows what was measured
        // (a card that keeps its bottom edge sits on the window's bottom: when it grows before the window has moved and
        // resized, it is still drawn where it belongs, cut at the top, not a line too low)
        Column(Modifier.wrapContentSize(if (bottomEdge != null && !toolbar) Alignment.BottomCenter else Alignment.TopCenter, unbounded = true),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (toolbar && above) { toolbarView(); Spacer(Modifier.height(gap.dp)) }
            Box {
                CaptionCard(o, prefs, fixedHeight = fixedH, maxHeight = screenAt(x + cardW / 2, y).height * 0.4f, slides = true, keepsBottom = bottomEdge != null,
                    modifier = Modifier.width(cardW.dp).onSizeChanged { cardH = (it.height / density).roundToInt() }
                        .pointerInput(locked) {
                            pressOrDrag(onClick = { if (locked) unlock = !unlock else toolbar = !toolbar },
                                onStart = { dragging = true; if (!locked) { y = cardTop(); bottomEdge = null } },
                                onDrag = { dx, dy -> if (!locked) { val (nx, ny) = place(x + dx, y + dy); x = nx; y = ny } },
                                onEnd = { dragging = false; if (!locked) { anchor(); save() } })
                        },
                    corners = if (!toolbar || locked) null else { color ->
                        // resize marks: a faint "›" in each corner, pointing out of the card; drag one to resize from there
                        for ((al, left, top) in listOf(Triple(Alignment.TopStart, true, true), Triple(Alignment.TopEnd, false, true),
                                Triple(Alignment.BottomEnd, false, false), Triple(Alignment.BottomStart, true, false))) {
                            val rot = if (top) (if (left) 225f else 315f) else (if (left) 135f else 45f)
                            val cursor = if (top == left) Cursor.NW_RESIZE_CURSOR else Cursor.NE_RESIZE_CURSOR
                            Box(Modifier.align(al).size(24.dp).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
                                .pointerInput(Unit) {
                                    pressOrDrag(onClick = {}, onStart = {
                                        // the height the pointer drags: the fixed one, or the text area measured now
                                        if (fixedH <= 0f) fixedH = (cardH - 20).toFloat().coerceAtLeast(30f)
                                        dragging = true; y = cardTop(); bottomEdge = null
                                    }, onDrag = { dx, dy ->
                                        // the opposite corner stays put; the card never grows past the edge of its screen
                                        val right = x + cardW; val bottom = y + fixedH
                                        val w = (cardW + if (left) -dx else dx).coerceIn(screen.width * Prefs.MIN_WIDTH, here.width.toFloat())
                                        val h = (fixedH + if (top) -dy else dy).coerceIn(30f, here.height * 0.4f)
                                        if (left) x = right - w
                                        if (top) y = (bottom - h).coerceAtLeast(here.y.toFloat())
                                        cardW = w; fixedH = if (top) bottom - y else h
                                        val (nx, ny) = place(x, y); x = nx; y = ny
                                    }, onEnd = { dragging = false; anchor(); save() })
                                }, contentAlignment = Alignment.Center) {
                                Text("›", color = color.copy(alpha = 0.5f), fontSize = 16.sp, modifier = Modifier.rotate(rot))
                            }
                        }
                    })
                // locked: a click shows this in the bottom-right corner for a few seconds; clicking it unlocks
                if (locked && unlock) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(Color(0xF526262B), RoundedCornerShape(8.dp)).clickableNoRipple { locked = false; unlock = false }
                    .padding(horizontal = 10.dp, vertical = 6.dp)) { Text("解锁", color = Color.White, fontSize = 13.sp) }
            }
            if (toolbar && !above) { Spacer(Modifier.height(gap.dp)); toolbarView() }
        }
    }
}
