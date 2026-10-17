package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ayati.volumetap.R
import com.ayati.volumetap.data.DragLane
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 対象アプリの上に重ねる調整の画面（design-pageturn.md §7.3.4〜7.3.6）。
 *
 * ユーザー補助の重ね表示（`TYPE_ACCESSIBILITY_OVERLAY`）を使うので、追加の権限は要らない。
 * 描くだけで、下のアプリの内容は読まない。
 *
 * - 待機：下端の小さな帯だけ。帯の外のタッチは下のアプリに届く
 * - 調整：画面全体に重ね、線とつまみを動かす。座標はディスプレイの実寸と一致する（§7.3.9.1）
 *
 * Compose ではなく View で組む。サービスのウィンドウで Compose を使うには Lifecycle などを
 * 自分で用意する必要があり、描くものも線と円とボタン数個なので。
 */
class CalibrationOverlay(
    private val service: AccessibilityService,
    private val listener: Listener,
) {

    interface Listener {
        fun onBegin()
        fun onCancel()
        fun onSave()
        fun onTest()
        fun onReset()
        fun onSelectDirection(direction: DragDirection)
        fun onLaneChanged(lane: DragLane)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val context: Context =
        ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault_Light_NoActionBar)

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var adjust: AdjustView? = null

    /** 「試す」の最中か。最中は重ね表示がタッチを受け付けない。 */
    private var testing = false

    /** 試している最中に重ね表示がタッチを受け取った（＝まだ受け付けない状態になっていなかった）。 */
    private var testFailed = false

    val isShowing: Boolean get() = root != null

    // --- 表示 -------------------------------------------------------------

    fun showBand(appLabel: String) {
        if (root != null && adjust == null) return
        hide()
        val band = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 32, 33, 36))
                cornerRadius = dp(16).toFloat()
            }
            addView(TextView(context).apply {
                text = service.getString(R.string.calibration_band_text, appLabel)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(button(R.string.calibration_cancel) { listener.onCancel() })
                addView(button(R.string.calibration_begin) { listener.onBegin() })
            })
        }
        val frame = TestGuardFrame(context).apply {
            setPadding(dp(8), 0, dp(8), dp(8))
            addView(band)
        }
        val lp = baseParams(
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            fullScreen = false,
        ).apply { gravity = Gravity.BOTTOM }
        add(frame, lp)
    }

    fun showAdjust(lane: DragLane, direction: DragDirection, startRange: ClosedFloatingPointRange<Float>) {
        hide()
        val view = AdjustView(context, startRange)
        adjust = view
        add(view, baseParams(WindowManager.LayoutParams.MATCH_PARENT, fullScreen = true))
        view.update(lane, direction)
    }

    fun update(lane: DragLane, direction: DragDirection) {
        adjust?.update(lane, direction)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
        adjust = null
        testing = false
    }

    private fun add(view: View, lp: WindowManager.LayoutParams) {
        try {
            wm.addView(view, lp)
            root = view
            params = lp
        } catch (e: RuntimeException) {
            // 重ね表示を出せない端末・状態。調整はできないが、サービスは落とさない。
            Log.w(TAG, "failed to show overlay", e)
            listener.onCancel()
        }
    }

    private fun baseParams(height: Int, fullScreen: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (fullScreen) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (fullScreen) {
                // ディスプレイ全体に広げ、dispatchGesture の座標と一致させる（§7.3.6）。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }

    // --- 試す -------------------------------------------------------------

    /**
     * 重ね表示がタッチを受け付けない状態にしてから、ジェスチャを出す。
     *
     * 注入したジェスチャは、その位置でいちばん上にあるウィンドウに届く。受け付けたままだと
     * 重ね表示自身が受け取り、下のアプリは動かない。受け付けない状態への切り替えは反映に
     * 数十 ms かかる（§7.3.9.1。0ms・16ms では間に合わず、50ms で届いた）ので、待ってから出す。
     *
     * @param fire ジェスチャを出す。完了・取り消し・失敗のいずれでも onDone を呼ぶこと。
     */
    fun runUntouchable(fire: (onDone: () -> Unit) -> Boolean) {
        if (root == null) {
            fire {}
            return
        }
        if (testing) return
        testing = true
        testFailed = false
        setTouchable(false)
        handler.postDelayed({
            if (!fire { handler.post(::endTest) }) endTest()
        }, UNTOUCHABLE_DELAY_MS)
        // 待ち行列から捨てられたなど、完了の知らせが来なかったときの保険。
        handler.postDelayed(testTimeout, TEST_TIMEOUT_MS)
    }

    private val testTimeout = Runnable { endTest() }

    private fun endTest() {
        if (!testing) return
        handler.removeCallbacks(testTimeout)
        testing = false
        setTouchable(true)
        if (testFailed) adjust?.showMessage(R.string.calibration_test_failed)
    }

    private fun setTouchable(touchable: Boolean) {
        val v = root ?: return
        val lp = params ?: return
        lp.flags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // --- 部品 -------------------------------------------------------------

    private fun button(textRes: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = service.getString(textRes)
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).roundToInt()

    /**
     * 試している最中に届いたタッチを捨て、失敗として記録する。受け付けない状態への
     * 切り替えが間に合わず、注入したジェスチャを重ね表示が受け取った場合にここへ来る。
     */
    private open inner class TestGuardFrame(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (testing) {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    Log.w(TAG, "overlay received a touch while testing")
                    testFailed = true
                }
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 調整の画面。下に操作パネル、上に線とつまみ（つまみのタッチを優先する）。 */
    @SuppressLint("ViewConstructor")
    private inner class AdjustView(
        context: Context,
        startRange: ClosedFloatingPointRange<Float>,
    ) : TestGuardFrame(context) {

        private val lanes = LaneView(context, startRange)
        private val panel = LinearLayout(context)
        private val amountLabel = TextView(context)
        private val message = TextView(context)
        private val forwardButton = button(R.string.calibration_forward) {
            listener.onSelectDirection(DragDirection.FORWARD)
        }
        private val backwardButton = button(R.string.calibration_backward) {
            listener.onSelectDirection(DragDirection.BACKWARD)
        }
        private var panelAtTop = false

        /**
         * 操作パネルをステータスバー・ナビゲーションバーに重ねないための余白。
         * 全面の重ね表示は自分のウィンドウにシステムバーの分を受け取らない（§7.3.9.1 で
         * view の systemBars は 0 だった）ので、ディスプレイ全体の値から求める。
         */
        private val insetTop: Int
        private val insetBottom: Int

        init {
            var top = dp(32)
            var bottom = dp(32)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top + dp(8)
                bottom = bars.bottom + dp(8)
            }
            insetTop = top
            insetBottom = bottom
            panel.apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.argb(250, 255, 255, 255))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.argb(80, 0, 0, 0))
                }
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(forwardButton)
                    addView(backwardButton)
                    addView(amountLabel.apply {
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        gravity = Gravity.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(message.apply {
                    setTextColor(Color.DKGRAY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setText(R.string.calibration_hint)
                })
                addView(LinearLayout(context).apply {
                    addView(button(R.string.calibration_test) { listener.onTest() })
                    addView(button(R.string.calibration_reset) { listener.onReset() })
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(button(R.string.calibration_cancel) { listener.onCancel() })
                    addView(button(R.string.calibration_save) { listener.onSave() })
                })
            }
            // 後に足したものが上に来る。つまみのタッチを操作パネルより優先する。
            addView(panel, panelParams())
            addView(lanes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun update(lane: DragLane, direction: DragDirection) {
            lanes.lane = lane
            lanes.direction = direction
            lanes.invalidate()
            amountLabel.text = service.getString(R.string.calibration_amount, (lane.amount * 100).roundToInt())
            forwardButton.isSelected = direction == DragDirection.FORWARD
            backwardButton.isSelected = direction == DragDirection.BACKWARD
            forwardButton.alpha = if (direction == DragDirection.FORWARD) 1f else 0.5f
            backwardButton.alpha = if (direction == DragDirection.BACKWARD) 1f else 0.5f
            placePanel(lanes.focusY())
        }

        fun showMessage(textRes: Int) {
            message.setText(textRes)
            message.setTextColor(Color.rgb(176, 0, 32))
        }

        /** 操作パネルを、いま動かしている（または選んでいる）つまみの反対側に寄せる。 */
        private fun placePanel(focusRatio: Float) {
            val top = focusRatio > 0.5f
            if (top == panelAtTop && panel.layoutParams != null) return
            panelAtTop = top
            panel.layoutParams = panelParams()
        }

        private fun panelParams(): LayoutParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = if (panelAtTop) Gravity.TOP else Gravity.BOTTOM
                setMargins(dp(8), if (panelAtTop) insetTop else 0, dp(8), if (panelAtTop) 0 else insetBottom)
            }

        /** 線とつまみ。つまみの上で始まったタッチだけを受け取り、それ以外は操作パネルへ通す。 */
        @SuppressLint("ViewConstructor")
        private inner class LaneView(
            context: Context,
            private val startRange: ClosedFloatingPointRange<Float>,
        ) : View(context) {

            var lane: DragLane = DragLane.DEFAULT
            var direction: DragDirection = DragDirection.FORWARD

            private var grabbing: Grab? = null
            private var grabOffsetX = 0f
            private var grabOffsetY = 0f

            private val accent = Color.rgb(25, 118, 210)
            private val zonePaint = Paint().apply { color = Color.argb(90, 0, 0, 0) }
            private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sp(12f)
            }
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                strokeWidth = dp(5).toFloat()
                strokeCap = Paint.Cap.ROUND
            }
            private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeWidth = dp(9).toFloat()
                strokeCap = Paint.Cap.ROUND
            }
            private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 25, 118, 210)
                strokeWidth = dp(3).toFloat()
                pathEffect = DashPathEffect(floatArrayOf(dp(10).toFloat(), dp(8).toFloat()), 0f)
            }
            private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = dp(4).toFloat()
            }
            private val endFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 25, 118, 210) }
            private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = sp(14f)
            }

            private val handleRadius = dp(18).toFloat()

            /** つまみの当たり判定。指より大きくする（48dp 相当）。 */
            private val hitRadius = dp(32).toFloat()

            private fun sp(v: Float): Float =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

            private fun startOf(d: DragDirection) =
                if (d == DragDirection.FORWARD) lane.forwardFrom else lane.backwardFrom

            private fun endOf(d: DragDirection) =
                if (d == DragDirection.FORWARD) lane.forwardTo else lane.backwardTo

            /** 操作パネルの位置を決める基準：つかんでいるつまみ、無ければ選んでいる向きの始点。 */
            fun focusY(): Float = when (grabbing) {
                Grab.END -> endOf(direction)
                else -> startOf(direction)
            }

            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                // 指を置けない範囲（システムジェスチャ領域）を薄く塗る。
                val zoneTop = startRange.start * h
                val zoneBottom = startRange.endInclusive * h
                canvas.drawRect(0f, 0f, w, zoneTop, zonePaint)
                canvas.drawRect(0f, zoneBottom, w, h, zonePaint)
                val zoneLabel = service.getString(R.string.calibration_zone)
                canvas.drawText(zoneLabel, dp(12).toFloat(), zoneBottom + sp(16f), zoneText)

                val x = lane.x * w
                // 選んでいない向きは点線。終点は送り量を共有するので一緒に動く。
                val other = if (direction == DragDirection.FORWARD) DragDirection.BACKWARD else DragDirection.FORWARD
                canvas.drawLine(x, startOf(other) * h, x, endOf(other) * h, dashedPaint)

                val y0 = startOf(direction) * h
                val y1 = endOf(direction) * h
                canvas.drawLine(x, y0, x, y1, outlinePaint)
                canvas.drawLine(x, y0, x, y1, linePaint)
                // 終点：塗りつぶしの円。始点：白抜きの円。
                canvas.drawCircle(x, y1, handleRadius + dp(3), handleFill)
                canvas.drawCircle(x, y1, handleRadius, endFill)
                canvas.drawCircle(x, y0, handleRadius, handleFill)
                canvas.drawCircle(x, y0, handleRadius, handleStroke)

                // 送り量を線の横に出す。線が右に寄っていれば左側に出す。
                val label = service.getString(R.string.calibration_amount, (lane.amount * 100).roundToInt())
                val tw = labelText.measureText(label)
                val pad = dp(8).toFloat()
                val lx = if (lane.x < 0.6f) x + handleRadius + pad else x - handleRadius - pad - tw - pad * 2
                val ly = (y0 + y1) / 2f
                canvas.drawRoundRect(
                    RectF(lx, ly - sp(14f), lx + tw + pad * 2, ly + sp(8f)),
                    pad, pad, labelBg,
                )
                canvas.drawText(label, lx + pad, ly + sp(2f), labelText)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val x = lane.x * w
                        val y0 = startOf(direction) * h
                        val y1 = endOf(direction) * h
                        grabbing = when {
                            near(event, x, y0) -> Grab.START
                            near(event, x, y1) -> Grab.END
                            else -> null
                        } ?: return false
                        grabOffsetX = x - event.x
                        grabOffsetY = (if (grabbing == Grab.START) y0 else y1) - event.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val grab = grabbing ?: return false
                        val rx = (event.x + grabOffsetX) / w
                        val ry = (event.y + grabOffsetY) / h
                        val next = when (grab) {
                            // 始点：線ごと動く（横位置も変わる）。送り量は変えない。
                            Grab.START -> {
                                val moved = lane.withX(rx)
                                if (direction == DragDirection.FORWARD) {
                                    moved.withForwardFrom(ry, startRange)
                                } else {
                                    moved.withBackwardFrom(ry, startRange)
                                }
                            }
                            // 終点：送り量だけが変わる。
                            Grab.END -> lane.withAmount(
                                if (direction == DragDirection.FORWARD) lane.forwardFrom - ry else ry - lane.backwardFrom,
                            )
                        }
                        if (next != lane) listener.onLaneChanged(next)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasGrabbing = grabbing != null
                        grabbing = null
                        if (wasGrabbing) placePanel(focusY())
                        return wasGrabbing
                    }
                }
                return grabbing != null
            }

            private fun near(event: MotionEvent, x: Float, y: Float): Boolean =
                abs(event.x - x) <= hitRadius && abs(event.y - y) <= hitRadius
        }
    }

    private enum class Grab { START, END }

    companion object {
        private const val TAG = "CalibrationOverlay"

        /** 受け付けない状態への切り替えを待つ時間（§7.3.9.1 で 50ms なら届いた。余裕をみる）。 */
        private const val UNTOUCHABLE_DELAY_MS = 100L

        /** 完了の知らせが来なかったときに、受け付ける状態へ戻すまでの時間。 */
        private const val TEST_TIMEOUT_MS = 2_000L
    }
}
