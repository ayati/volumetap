package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Phase 2-0 の実機スパイク（design-pageturn.md §7.3.9）。デバッグビルドでのみ使う。
 *
 * ユーザー補助の重ね表示（`TYPE_ACCESSIBILITY_OVERLAY`）について、次を確かめる。
 * - 全面の重ね表示の座標と dispatchGesture の座標が一致するか（`probe`）
 * - FLAG_NOT_TOUCHABLE に切り替えた直後の注入が、下のアプリに届くか（`fire` + `nt`）
 * - 終点を画面の端ぎりぎりにしたドラッグが、通知やホームにならないか（`fire`）
 * - システムジェスチャ領域の高さが取れるか（表示時にログへ出す）
 * - 重ね表示を出しても前面アプリの記録が保たれるか（[onEvent] でログへ出す）
 *
 * ```
 * adb shell am broadcast -a com.ayati.volumetap.debug.OVERLAY -p com.ayati.volumetap --es show full
 * adb shell am broadcast -a com.ayati.volumetap.debug.OVERLAY -p com.ayati.volumetap --es show band
 * adb shell am broadcast -a com.ayati.volumetap.debug.OVERLAY -p com.ayati.volumetap --es show none
 * adb shell am broadcast -a com.ayati.volumetap.debug.OVERLAY -p com.ayati.volumetap --es fire probe
 * adb shell am broadcast -a com.ayati.volumetap.debug.OVERLAY -p com.ayati.volumetap \
 *     --es fire drag --ef x 0.5 --ef from 0.92 --ef to 0.0 --ez nt true --ei ntdelay 0
 * ```
 *
 * 状態はプロセス内にだけ持つ。サービスが再起動したら消える。
 */
@SuppressLint("ViewConstructor")
class OverlaySpike(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: SpikeView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 重ね表示を出しているあいだ、前面アプリのイベントをすべてログに出す。 */
    val active: Boolean get() = view != null

    fun handle(intent: Intent) {
        intent.getStringExtra("show")?.let { show(it) }
        when (intent.getStringExtra("fire")) {
            "probe" -> probe()
            "drag" -> drag(
                x = intent.getFloatExtra("x", 0.5f),
                from = intent.getFloatExtra("from", 0.80f),
                to = intent.getFloatExtra("to", 0.17f),
                notTouchable = intent.getBooleanExtra("nt", false),
                ntDelayMs = intent.getIntExtra("ntdelay", 0).toLong(),
            )
        }
    }

    fun onEvent(type: Int, packageName: String?) {
        if (active) Log.i(TAG, "event type=$type pkg=$packageName")
    }

    // --- 重ね表示 ---------------------------------------------------------

    private fun show(mode: String) {
        remove()
        if (mode == "none") return
        val full = mode == "full"
        val v = SpikeView(service, full)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (full) WindowManager.LayoutParams.MATCH_PARENT else dp(96),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (full) Gravity.TOP or Gravity.START else Gravity.BOTTOM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // システムバーの分を避けずに、ディスプレイ全体に広げる。
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        view = v
        params = lp
        Log.i(TAG, "shown $mode real=${ScreenMetrics.realSize(service)}")
        logInsets()
        v.post {
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            Log.i(TAG, "view size=${v.width}x${v.height} onScreen=(${loc[0]}, ${loc[1]})")
            v.rootWindowInsets?.let { logViewInsets(it) }
        }
    }

    private fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    fun reset() = remove()

    private fun setTouchable(touchable: Boolean) {
        val v = view ?: return
        val lp = params ?: return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.updateViewLayout(v, lp)
        Log.i(TAG, "touchable=$touchable")
    }

    private fun logInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val insets = wm.currentWindowMetrics.windowInsets
        Log.i(
            TAG,
            "metrics insets systemGestures=${insets.getInsets(WindowInsets.Type.systemGestures())} " +
                "mandatory=${insets.getInsets(WindowInsets.Type.mandatorySystemGestures())} " +
                "systemBars=${insets.getInsets(WindowInsets.Type.systemBars())} " +
                "cutout=${insets.getInsets(WindowInsets.Type.displayCutout())}",
        )
    }

    private fun logViewInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        Log.i(
            TAG,
            "view insets systemGestures=${insets.getInsets(WindowInsets.Type.systemGestures())} " +
                "mandatory=${insets.getInsets(WindowInsets.Type.mandatorySystemGestures())} " +
                "systemBars=${insets.getInsets(WindowInsets.Type.systemBars())}",
        )
    }

    // --- 注入 -------------------------------------------------------------

    /** 既知の位置へ順にタップを注入し、重ね表示が受け取った座標と比べる。 */
    private fun probe() {
        val points = listOf(
            0.5f to 0.5f, 0.5f to 0.005f, 0.5f to 0.995f, 0.005f to 0.5f, 0.995f to 0.5f,
            0.1f to 0.1f, 0.9f to 0.9f,
        )
        fun next(i: Int) {
            if (i >= points.size) return
            val (rx, ry) = points[i]
            val size = ScreenMetrics.realSize(service)
            val x = (rx * size.x).coerceIn(0f, size.x - 1f)
            val y = (ry * size.y).coerceIn(0f, size.y - 1f)
            Log.i(TAG, "probe#$i expect=($x, $y)")
            view?.expected = x to y
            val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0L, 60L)
            dispatch(stroke, "probe#$i") { handler.postDelayed({ next(i + 1) }, 400) }
        }
        next(0)
    }

    /** 端よけなしのドラッグ（終点を画面の端ぎりぎりにする確認用）。 */
    private fun drag(x: Float, from: Float, to: Float, notTouchable: Boolean, ntDelayMs: Long) {
        val size = ScreenMetrics.realSize(service)
        val px = (x * size.x).coerceIn(0f, size.x - 1f)
        val y0 = (from * size.y).coerceIn(0f, size.y - 1f)
        val y1 = (to * size.y).coerceIn(0f, size.y - 1f)
        Log.i(TAG, "drag x=$px $y0 -> $y1 (${y1 - y0}px) nt=$notTouchable delay=$ntDelayMs")

        fun start() {
            val move = Path().apply { moveTo(px, y0); lineTo(px, y1) }
            val first = GestureDescription.StrokeDescription(move, 0L, DragGeometry.MOVE_MS, true)
            dispatch(first, "drag move", onCancelled = { if (notTouchable) setTouchable(true) }) {
                val stay = Path().apply { moveTo(px, y1) }
                val second = first.continueStroke(stay, 0L, DragGeometry.HOLD_MS, false)
                dispatch(second, "drag hold", onCancelled = { if (notTouchable) setTouchable(true) }) {
                    if (notTouchable) setTouchable(true)
                }
            }
        }
        if (notTouchable) {
            setTouchable(false)
            handler.postDelayed(::start, ntDelayMs)
        } else {
            start()
        }
    }

    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        label: String,
        onCancelled: () -> Unit = {},
        onCompleted: () -> Unit,
    ) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    Log.i(TAG, "completed: $label")
                    onCompleted()
                }

                override fun onCancelled(g: GestureDescription?) {
                    Log.i(TAG, "cancelled: $label")
                    onCancelled()
                }
            },
            null,
        )
        if (!ok) Log.w(TAG, "rejected: $label")
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    /** 10% ごとの目盛りと、受け取ったタッチの位置を描く。 */
    private class SpikeView(context: Context, private val full: Boolean) : View(context) {
        var expected: Pair<Float, Float>? = null
        private var touched: Pair<Float, Float>? = null
        private val line = Paint().apply { color = Color.argb(160, 255, 0, 160); strokeWidth = 3f }
        private val text = Paint().apply {
            color = Color.argb(220, 255, 0, 160)
            textSize = 36f
            isAntiAlias = true
        }
        private val hit = Paint().apply { color = Color.argb(200, 0, 160, 255); strokeWidth = 4f }

        override fun onDraw(canvas: Canvas) {
            if (!full) {
                canvas.drawColor(Color.argb(200, 40, 40, 40))
                canvas.drawText("spike band", 32f, height / 2f, text)
                return
            }
            canvas.drawColor(Color.argb(40, 255, 0, 160))
            for (i in 0..10) {
                val y = height * i / 10f
                canvas.drawLine(0f, y, width.toFloat(), y, line)
                canvas.drawText("${i * 10}%", 16f, y.coerceIn(40f, height - 8f), text)
            }
            canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), line)
            touched?.let { (x, y) ->
                canvas.drawLine(x - 60, y, x + 60, y, hit)
                canvas.drawLine(x, y - 60, x, y + 60, hit)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val loc = IntArray(2).also { getLocationOnScreen(it) }
                Log.i(
                    TAG,
                    "touch local=(${event.x}, ${event.y}) raw=(${event.rawX}, ${event.rawY}) " +
                        "onScreen=(${loc[0]}, ${loc[1]}) expected=$expected",
                )
                touched = event.x to event.y
                invalidate()
            }
            return true
        }
    }

    companion object {
        const val ACTION = "com.ayati.volumetap.debug.OVERLAY"
        private const val TAG = "OverlaySpike"
    }
}
