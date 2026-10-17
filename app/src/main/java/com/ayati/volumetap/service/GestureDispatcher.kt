package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * ディスプレイ全体の実寸（ステータスバー・ナビゲーションバーを含む）。
 *
 * [android.view.Display.getSize] 系は不可視領域を除外するため、
 * [AccessibilityService.dispatchGesture] の座標系と一致しない（設計書 §5.2）。
 */
object ScreenMetrics {
    fun realSize(context: Context): Point {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }
}

/** [AccessibilityService.dispatchGesture] によるタップ注入（設計書 §5.2）。 */
class GestureDispatcher(private val service: AccessibilityService) {

    /**
     * 画面比率で指定した位置を 1 回タップする。
     *
     * @return ジェスチャの登録に成功したら true。実際に届いたかは非同期で
     *   [GestureResultCallback] に返るため、ここでの true は「発行できた」ことのみを意味する。
     */
    fun tap(ratioX: Float, ratioY: Float, durationMs: Long = TAP_DURATION_MS): Boolean {
        val size = ScreenMetrics.realSize(service)
        if (size.x <= 0 || size.y <= 0) {
            Log.w(TAG, "invalid screen size: $size")
            return false
        }
        // 端ぴったりの座標はウィンドウ外と判定されうるため 1px 内側に丸める。
        val x = (ratioX.coerceIn(0f, 1f) * size.x).coerceIn(0f, size.x - 1f)
        val y = (ratioY.coerceIn(0f, 1f) * size.y).coerceIn(0f, size.y - 1f)

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        val dispatched = service.dispatchGesture(gesture, resultCallback, null)
        if (!dispatched) {
            // サービスが接続されていない、または他のジェスチャと競合した場合。
            Log.w(TAG, "dispatchGesture rejected at ($x, $y)")
        }
        return dispatched
    }

    /** ドラッグの実行中か。新しい注入は実行中のジェスチャを取り消すため、重ねて出さない。 */
    private var dragging = false

    /** 実行中に来た次のドラッグ。1 件だけ待たせ、それ以上は捨てる（design-pageturn.md §5.3）。 */
    private var pendingDrag: (() -> Unit)? = null

    /**
     * 画面比率で指定した 2 点間をドラッグする（design-pageturn.md §5.1）。
     *
     * 動かしたまま離すと慣性スクロール（フリング）になるため、移動の後に [holdMs] だけ
     * 静止してから離す。静止は [GestureDescription.StrokeDescription.continueStroke] で
     * 第 2 ストロークとして出す。[holdMs] が 0 なら静止せずに離す。
     *
     * @return 発行または待ち行列への追加に成功したら true。
     */
    fun drag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        dragMs: Long,
        holdMs: Long,
    ): Boolean {
        if (dragging) {
            if (pendingDrag != null) {
                Log.i(TAG, "drag dropped: another one is already waiting")
                return false
            }
            Log.i(TAG, "drag queued")
            pendingDrag = { drag(fromX, fromY, toX, toY, dragMs, holdMs) }
            return true
        }
        val size = ScreenMetrics.realSize(service)
        if (size.x <= 0 || size.y <= 0) {
            Log.w(TAG, "invalid screen size: $size")
            return false
        }
        // 端から始めるとシステムジェスチャ（ホーム・戻る・通知）になるため内側に丸める（§5.5）。
        val x0 = fromX.coerceIn(EDGE_X, 1f - EDGE_X) * size.x
        val y0 = fromY.coerceIn(EDGE_Y, 1f - EDGE_Y) * size.y
        val x1 = toX.coerceIn(EDGE_X, 1f - EDGE_X) * size.x
        val y1 = toY.coerceIn(EDGE_Y, 1f - EDGE_Y) * size.y

        val move = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val hold = holdMs > 0
        val first = GestureDescription.StrokeDescription(move, 0L, dragMs, hold)
        val startedAt = SystemClock.uptimeMillis()
        Log.i(TAG, "drag start ($x0, $y0) -> ($x1, $y1) in ${dragMs}ms, hold ${holdMs}ms")

        val onMoved = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val movedAt = SystemClock.uptimeMillis()
                Log.i(TAG, "drag moved after ${movedAt - startedAt}ms")
                if (!hold) {
                    finishDrag()
                    return
                }
                val stay = Path().apply { moveTo(x1, y1) }
                val second = GestureDescription.Builder()
                    .addStroke(first.continueStroke(stay, 0L, holdMs, false))
                    .build()
                val onReleased = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        val now = SystemClock.uptimeMillis()
                        Log.i(TAG, "drag released after ${now - startedAt}ms (gap+hold ${now - movedAt}ms)")
                        finishDrag()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "drag hold cancelled")
                        finishDrag()
                    }
                }
                if (!service.dispatchGesture(second, onReleased, null)) {
                    Log.w(TAG, "dispatchGesture rejected the hold stroke")
                    finishDrag()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "drag move cancelled")
                finishDrag()
            }
        }

        val gesture = GestureDescription.Builder().addStroke(first).build()
        if (!service.dispatchGesture(gesture, onMoved, null)) {
            Log.w(TAG, "dispatchGesture rejected the drag")
            return false
        }
        dragging = true
        return true
    }

    private fun finishDrag() {
        dragging = false
        val next = pendingDrag ?: return
        pendingDrag = null
        next()
    }

    private val resultCallback = object : AccessibilityService.GestureResultCallback() {
        override fun onCancelled(gestureDescription: GestureDescription?) {
            // FLAG_SECURE の画面やシステム UI 上ではここに来る（設計書 §8）。
            Log.i(TAG, "gesture cancelled")
        }
    }

    companion object {
        private const val TAG = "GestureDispatcher"

        /** 注入するタップの押下時間。短すぎると取りこぼすアプリがある。 */
        const val TAP_DURATION_MS = 60L

        /** ドラッグの始点・終点を画面端から離す割合（design-pageturn.md §5.5）。 */
        private const val EDGE_X = 0.15f
        private const val EDGE_Y = 0.08f
    }
}
