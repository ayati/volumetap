package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
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

/**
 * [AccessibilityService.dispatchGesture] によるタップ・ドラッグの注入
 * （設計書 §5.2、design-pageturn.md §5.1）。
 *
 * タップもドラッグも [GestureQueue] を通す。新しい注入は実行中のジェスチャを取り消すため、
 * 重ねて出さない。
 */
class GestureDispatcher(private val service: AccessibilityService) {

    private val queue = GestureQueue()

    /**
     * 画面比率で指定した位置を 1 回タップする。
     *
     * @return 発行または順番待ちに入れたら true。実際に届いたかは非同期で
     *   [GestureResultCallback] に返るため、ここでの true は「発行できた」ことのみを意味する。
     */
    fun tap(ratioX: Float, ratioY: Float): Boolean = submit("tap") {
        val size = screenSize() ?: return@submit false
        // 端ぴったりの座標はウィンドウ外と判定されうるため 1px 内側に丸める。
        val x = (ratioX.coerceIn(0f, 1f) * size.x).coerceIn(0f, size.x - 1f)
        val y = (ratioY.coerceIn(0f, 1f) * size.y).coerceIn(0f, size.y - 1f)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        dispatch(stroke, "tap at ($x, $y)") { queue.finish() }
    }

    /**
     * 画面比率で指定した 2 点間をドラッグする。
     *
     * 動かしたまま離すと慣性スクロール（フリング）になるため、移動の後に
     * [DragGeometry.HOLD_MS] だけ静止してから離す。静止は
     * [GestureDescription.StrokeDescription.continueStroke] で第 2 ストロークとして出す。
     * 試作機では第 1・第 2 ストロークの隙間は 1〜6ms で、指が離れた扱いにはならなかった。
     */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean = submit("drag") {
        val size = screenSize() ?: return@submit false
        // 端から始めるとシステムジェスチャ（ホーム・戻る・通知）になるため内側に丸める。
        val x0 = fromX.coerceIn(EDGE_X, 1f - EDGE_X) * size.x
        val y0 = fromY.coerceIn(EDGE_Y, 1f - EDGE_Y) * size.y
        val x1 = toX.coerceIn(EDGE_X, 1f - EDGE_X) * size.x
        val y1 = toY.coerceIn(EDGE_Y, 1f - EDGE_Y) * size.y

        val move = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val first = GestureDescription.StrokeDescription(move, 0L, DragGeometry.MOVE_MS, true)
        dispatch(first, "drag ($x0, $y0) -> ($x1, $y1)") {
            val stay = Path().apply { moveTo(x1, y1) }
            val second = first.continueStroke(stay, 0L, DragGeometry.HOLD_MS, false)
            if (!dispatch(second, "drag hold") { queue.finish() }) queue.finish()
        }
    }

    /** 順番待ちを捨てる。長押しで音量操作が始まったら、溜まっていた送りは不要。 */
    fun dropPending() = queue.dropPending()

    fun reset() = queue.reset()

    private fun submit(label: String, start: () -> Boolean): Boolean {
        val accepted = queue.submit(start)
        if (!accepted) Log.i(TAG, "$label dropped")
        return accepted
    }

    /**
     * ストロークを 1 本発行する。[onCompleted] は完了時だけ呼ぶ。取り消し時は
     * 順番待ちを進めるだけにする（ドラッグの第 2 ストロークを出さない）。
     */
    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        label: String,
        onCompleted: () -> Unit,
    ): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onCompleted()

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // FLAG_SECURE の画面やシステム UI 上、利用者が画面に触れたときはここに来る。
                Log.i(TAG, "cancelled: $label")
                queue.finish()
            }
        }
        val dispatched = service.dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            // サービスが接続されていない場合など。
            Log.w(TAG, "dispatchGesture rejected: $label")
        }
        return dispatched
    }

    private fun screenSize(): Point? {
        val size = ScreenMetrics.realSize(service)
        if (size.x <= 0 || size.y <= 0) {
            Log.w(TAG, "invalid screen size: $size")
            return null
        }
        return size
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
