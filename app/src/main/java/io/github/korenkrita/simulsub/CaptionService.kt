package io.github.korenkrita.simulsub

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.io.File

/**
 * Foreground service: playback capture → [CaptionSession] (Silero VAD → [Segmenter] → [Pipeline]) → overlay.
 * Everything heavy happens on its own threads; the main thread only renders the overlay.
 */
class CaptionService : Service(), SessionHost {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlayView? = null
    override val prefs by lazy { Prefs(this) }
    private val session by lazy { CaptionSession(this) }
    /**
     * The screen came on: a pause's timer does not count deep sleep, so a pause that ended while the phone slept
     * is shown as over now.
     */
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { session.refresh() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // the home screen's and the notification's 暂停/继续; a service started only for it has nothing to pause
        if (intent?.action == ACTION_PAUSE) { if (projection != null) togglePause() else stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        registerReceiver(screenOn, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() } // user revoked sharing
        }, main)
        projection = p
        running = true
        showOverlay()
        session.start()
        return START_NOT_STICKY
    }

    override fun post(r: () -> Unit) { main.post(r) }
    override fun postDelayed(r: () -> Unit, ms: Long): AutoCloseable {
        val run = Runnable(r)
        main.postDelayed(run, ms)
        return AutoCloseable { main.removeCallbacks(run) }
    }
    // counts deep sleep: a 429's pause ends on time while the phone sleeps
    override fun clock() = android.os.SystemClock.elapsedRealtime()
    override fun logInfo(m: String) { Log.i(TAG, m) }
    override fun logWarn(m: String, e: Throwable?) { Log.w(TAG, m, e) }
    override val logRoot: File get() = getExternalFilesDir(null)!!
    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override fun deviceInfo(byokUrl: String?): Map<String, Any?> =
        mapOf("device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    override val deviceWord = "手机"
    override val endHint = "点字幕再点 ✕ 结束"

    override fun loadModels(asrThreads: Int): SpeechModels {
        val vad = SileroProb(assets)
        val asr = runCatching { SherpaAsr(assets, asrThreads) }.getOrElse { vad.release(); throw it }
        return object : SpeechModels {
            override val asr: Asr = asr
            override fun vadProb(frame: FloatArray) = vad.prob(frame)
            override fun release() { asr.release(); vad.release() }
        }
    }

    @SuppressLint("MissingPermission") // checked in MainActivity
    override fun openAudio(log: SessionLog?): AudioSource {
        val p = projection ?: error("屏幕共享已结束")
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(16_000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, 16_000 * 4 * 2)) // 2 s of slack
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音（可能被其他 App 占用）" }
        } catch (e: Throwable) { rec.release(); throw e }
        return object : AudioSource {
            @Volatile private var cancelled = false
            private val ts = AudioTimestamp()
            private var framesRead = 0L
            override var estimated = true; private set
            // Non-blocking reads: cancel is only a flag, so the main thread never touches AudioRecord and the
            // session thread is the only one that calls stop()/release().
            override fun read(frame: FloatArray): Long {
                var off = 0
                while (off < frame.size) {
                    if (cancelled) return -1
                    val n = rec.read(frame, off, frame.size - off, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "读取音频失败 ($n)" }
                    if (n == 0) Thread.sleep(10)
                    off += n
                }
                framesRead += frame.size
                // Capture time of this frame's last sample, from the HAL timestamp when available,
                // so time spent waiting in the AudioRecord buffer counts as latency.
                val hal = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                estimated = !hal
                return if (hal) (ts.nanoTime + (framesRead - ts.framePosition) * 1_000_000_000L / 16_000) / 1_000_000
                else monoMs() // read time: underestimates latency by the time spent in the AudioRecord buffer
            }
            // playback capture does not end by itself: the user ends sharing, and the service stops
            override fun endReason(): String? = null
            override fun close() { runCatching { rec.stop() }; rec.release() }
            override fun cancel() { cancelled = true }
        }
    }

    override fun show(lines: List<CaptionLine>, diag: String?, mtLight: ServiceStatus.Light, jevLight: ServiceStatus.Light,
                      mtReason: String?, jevReason: String?) {
        val v = overlay ?: return
        v.setLines(lines.map { CaptionOverlayView.Line(it.zh, it.ja, it.isFinal, it.id) }, diag)
        relayoutCaptions()
        v.setServices(mtLight, jevLight, mtReason, jevReason)
    }
    // the captions and the message stay on the overlay until the user ends them (✕ or the notification)
    override fun ended() {}
    override fun finished() { stopSelf() }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val dm = resources.displayMetrics
        val v = CaptionOverlayView(this, interactive = true)
        v.setColors(CaptionOverlayView.palette(prefs.palette))
        v.applyStyle(prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), prefs.bgAlpha)
        // usable area: current window bounds (follows rotation) minus status / navigation bars. Only its size is
        // used: the overlay's x/y are already relative to that area (its parent frame excludes the bars).
        fun area(): android.graphics.Rect {
            if (android.os.Build.VERSION.SDK_INT < 30) return android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return android.graphics.Rect(m.bounds.left + i.left, m.bounds.top + i.top, m.bounds.right - i.right, m.bounds.bottom - i.bottom)
        }
        var cardW = (area().width() * prefs.widthFrac).toInt()
        var toolbarShown = false
        val lp = WindowManager.LayoutParams(
            cardW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX.takeIf { it >= 0 } ?: ((area().width() - cardW) / 2)
            y = prefs.overlayY.takeIf { it >= 0 } ?: (area().height() * 2 / 3)
            // no system move animation: otherwise a card that grows upward first shows a frame grown downward and
            // then glides back for ~200 ms, and a drag lags behind the finger. Hidden API (allowed as "unsupported",
            // test-api); if a later Android refuses it, windows just animate their moves again.
            runCatching { WindowManager.LayoutParams::class.java.getField("privateFlags").let { f -> f.setInt(this, f.getInt(this) or PRIVATE_FLAG_NO_MOVE_ANIMATION) } }
        }
        // Where the user put the card (its top-left in the usable area). The window is derived from it on every
        // layout: closed, the window is exactly the card, so no invisible area blocks touches to the app underneath;
        // open, it also covers the toolbar, which is centred under the card but shifted to stay on screen (and put
        // above the card when there is no room below). The card itself never moves when the toolbar opens.
        var cardX = lp.x; var cardY = lp.y
        var shownX = cardX; var shownY = cardY // where layout() last put the card (cardX / cardY clamped to the screen)
        // ≥ 0: the card keeps this bottom edge (px in the usable area) and grows upward; -1: it keeps its top edge
        var bottomY = -1
        fun layout() {
            val a = area(); val sw = a.width(); val sh = a.height()
            cardW = cardW.coerceIn((sw * Prefs.MIN_WIDTH).toInt(), sw)
            v.setCardWidth(cardW)
            // captions ≤ 40 %, status ≤ 20 % of the usable height: card + toolbar always fit on screen
            v.setMaxContentHeight((a.height() * MAX_CAPTION_FRAC).toInt(), (a.height() * 0.2f).toInt())
            // measured now at this width (a resize just changed width or caption height; the last layout is stale)
            val cardH = v.measureCardHeight(cardW).coerceAtLeast(1)
            if (bottomY >= 0) cardY = bottomY - cardH
            // cardX / cardY stay what the user chose; only the shown position is clamped (a rotation or a taller card
            // must not overwrite the placement, so rotating back puts the card where it was)
            shownX = cardX.coerceIn(0, sw - cardW)
            shownY = cardY.coerceIn(0, (sh - cardH).coerceAtLeast(0))
            val tbW = if (toolbarShown) v.toolbarWidthPx().coerceAtMost(sw) else 0
            val tbH = if (toolbarShown) v.toolbarHeightPx() else 0
            val tbX = (shownX + (cardW - tbW) / 2).coerceIn(0, sw - tbW)
            // below if it fits, else above if that fits; on a short screen with room on neither side, the roomier
            // side, the toolbar overlapping the card's edge by what is missing (it draws over the card)
            val below = sh - (shownY + cardH); val aboveRoom = shownY
            val above = toolbarShown && tbH > below && (tbH <= aboveRoom || aboveRoom > below)
            val overlap = if (!toolbarShown) 0 else (tbH - if (above) aboveRoom else below).coerceAtLeast(0)
            val left = if (toolbarShown) minOf(shownX, tbX) else shownX
            val right = if (toolbarShown) maxOf(shownX + cardW, tbX + tbW) else shownX + cardW
            v.placeCard(shownX - left, tbX - left, above, overlap)
            lp.x = left; lp.width = right - left
            lp.y = if (above) shownY - (tbH - overlap) else shownY
            if (v.isAttachedToWindow) wm.updateViewLayout(v, lp)
        }
        // rotation / resize: the caption height may not change, so re-fit explicitly from the saved width fraction
        // caption height from the saved fraction (0 = fit the text); kept within the 40 % cap by the view
        fun applyHeight() = v.setCaptionHeight((area().height() * prefs.heightFrac).toInt())
        // Once placed, a card that fits its text and sits in the lower half keeps its bottom edge: new text pushes
        // the lines above it up, and the line being read stays where it is. Higher up it keeps its top edge, so it
        // does not grow off the top of the screen; a fixed height does not grow at all.
        fun anchor() {
            val h = v.measureCardHeight(cardW)
            bottomY = if (prefs.heightFrac == 0f && shownY + h / 2 > area().height() / 2) shownY + h else -1
            layout()
        }
        // where the card is now becomes the saved placement (its bottom edge too, for a card that keeps it)
        fun savePlace() {
            prefs.overlayX = shownX; prefs.overlayY = shownY; prefs.overlayBottom = shownY + v.measureCardHeight(cardW)
        }
        // New text: a card that keeps its bottom edge moves its window in the same layout pass as the text grows.
        // (Placed from the top as always: a window placed from the bottom is measured against a parent frame that
        // loses the navigation bar when a full-screen video hides it, and would then sit lower than chosen.)
        relayoutCaptions = { if (bottomY >= 0) layout() }
        var captionH = 0 // px while a resize drag runs
        onDisplayChanged = { cardW = (area().width() * prefs.widthFrac).toInt(); applyHeight(); layout() }
        // gestures start from where the card is shown, and what they end on is the new placement
        v.onMove = { dx, dy -> bottomY = -1; cardX = shownX + dx; cardY = shownY + dy; layout() }
        // a corner drag moves that corner; the opposite edges stay where they are. The whole card rectangle is
        // derived from the fixed edges: the caption height is frozen at what is shown, and a width change that
        // rewraps the status line is measured at the new width, then the caption height absorbs the difference.
        v.onResize = { dx, dy, left, top ->
            bottomY = -1
            val a = area(); val sh = a.height(); val oldW = cardW
            val minW = (a.width() * Prefs.MIN_WIDTH).toInt()
            val oldH = v.measureCardHeight(oldW)
            val fixedTop = shownY; val fixedBottom = shownY + oldH
            val roomW = if (left) shownX + oldW else a.width() - shownX
            cardW = (cardW + if (left) -dx else dx).coerceIn(minW, roomW.coerceAtLeast(minW))
            cardX = if (left) shownX + oldW - cardW else shownX
            if (captionH == 0) captionH = v.captionHeightPx() // start from what is on screen (also when auto)
            val maxCap = (sh * MAX_CAPTION_FRAC).toInt()
            var want = (captionH + if (top) -dy else dy).coerceIn(v.minCaptionPx(), maxCap.coerceAtLeast(v.minCaptionPx()))
            v.setCaptionHeight(want)
            // the card may not grow past the screen edge it is growing toward
            val newH = v.measureCardHeight(cardW)
            val limit = if (top) fixedBottom else sh - fixedTop
            if (newH > limit) { want = (want - (newH - limit)).coerceAtLeast(v.minCaptionPx()); v.setCaptionHeight(want) }
            captionH = want
            cardY = if (top) fixedBottom - v.measureCardHeight(cardW) else fixedTop
            layout()
        }
        v.onMoveEnd = {
            cardX = shownX; cardY = shownY
            prefs.widthFrac = cardW.toFloat() / area().width()
            if (captionH > 0) { prefs.heightFrac = captionH.toFloat() / area().height(); captionH = 0 }
            anchor()
            savePlace()
        }
        v.onStyleChanged = { prefs.setStyle(v.textSp, v.bgAlpha) }
        v.onToolbarShown = { toolbarShown = it; layout() }
        v.onStop = { stopSelf() }
        v.onTogglePause = { togglePause() }
        v.setFinalOnly(prefs.finalOnly)
        v.onToggleMode = { prefs.finalOnly = !prefs.finalOnly } // the listener below updates the button and captions
        v.setShown(prefs.showJa, prefs.showStats)
        v.onToggleJa = { prefs.showJa = !prefs.showJa }
        v.onToggleStats = { prefs.showStats = !prefs.showStats }
        // taller text / toolbar / rotation: pull the window back on screen once the new height is known
        v.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) v.post { layout() }
        }
        // settings changed on the home screen while subtitles run
        prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "textSp", "bgAlpha" -> if (prefs.textSp != v.textSp || prefs.bgAlpha != v.bgAlpha)
                    v.applyStyle(prefs.textSp.coerceIn(CaptionOverlayView.MIN_SP, CaptionOverlayView.MAX_SP), prefs.bgAlpha)
                "widthFrac" -> (area().width() * prefs.widthFrac).toInt().let { if (it != cardW) { cardW = it; layout() } }
                "heightFrac" -> if (captionH == 0) { applyHeight(); layout(); anchor(); savePlace() }
                "showJa", "showStats" -> { v.setShown(prefs.showJa, prefs.showStats); session.prefChanged(key) }
                "palette" -> v.setColors(CaptionOverlayView.palette(prefs.palette))
                "finalOnly" -> { v.setFinalOnly(prefs.finalOnly); session.prefChanged(key) }
                // the captions and the translation settings are the session's
                else -> key?.let { session.prefChanged(it) }
            }
        }.also { prefs.listen(it) }
        v.setCardWidth(cardW)
        applyHeight()
        wm.addView(v, lp)
        overlay = v
        // a card saved in the lower half comes back with its bottom edge where it was, whatever its text is now
        val savedTop = prefs.overlayY; val savedBottom = prefs.overlayBottom
        val saved = savedTop >= 0 && savedBottom >= 0
        if (saved && prefs.heightFrac == 0f && (savedTop + savedBottom) / 2 > area().height() / 2) bottomY = savedBottom
        layout()
        if (!saved) anchor()
    }

    private var onDisplayChanged: (() -> Unit)? = null
    /** After new captions are set: re-places a card that keeps its bottom edge (see showOverlay). */
    private var relayoutCaptions: () -> Unit = {}

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { onDisplayChanged?.invoke() } // after the new window bounds are in effect
    }

    /** Strong reference: SharedPreferences only keeps listeners weakly. */
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onDestroy() {
        // Never blocks the main thread and never frees native objects: the session thread notices the stop,
        // finishes, waits for the pipeline workers and releases everything itself.
        session.stop()
        runCatching { unregisterReceiver(screenOn) }
        running = false; paused = false
        projection?.stop()
        projection = null
        prefListener?.let { prefs.unlisten(it) }; prefListener = null
        onDisplayChanged = null
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        super.onDestroy()
    }

    /** Main thread. */
    private fun togglePause() {
        val p = !session.paused
        session.setPaused(p); paused = p
        overlay?.setPaused(p)
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "字幕", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            this, 2, Intent(this, CaptionService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "cap")
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setColor(getColor(R.color.brand))
            .setContentTitle(if (paused) "同传字幕已暂停" else "同传字幕运行中")
            .setContentText(if (paused) "声音不再识别；点「继续」接着出字幕" else "点字幕可调整样式；点这里的「停止」结束")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, if (paused) "继续" else "暂停", pause).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "jpsub"
        const val EXTRA_CODE = "code"
        /** WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION (hidden) */
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        /** flips the running session's pause */
        const val ACTION_PAUSE = "pause"
        /** captions never take more than this part of the usable height, so the toolbar always fits */
        const val MAX_CAPTION_FRAC = 0.4f

        /** true from start until onDestroy (main thread); the home screen uses it to show 开始/停止. */
        @Volatile var running = false
            private set
        /** the running session is paused (main thread); false when none runs */
        @Volatile var paused = false
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, CaptionService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }

        fun togglePause(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_PAUSE))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptionService::class.java).setAction(ACTION_STOP))
        }
    }
}
