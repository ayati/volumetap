package com.ayati.volumetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import com.ayati.volumetap.data.DragLane

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

    /**
     * ドラッグの始点として指を置いてよい y の範囲（画面高に対する比率、design-pageturn.md §7.3.2）。
     *
     * 上下のシステムジェスチャ領域（上から下へなぞると通知、下から上へなぞるとホーム）の外側。
     * 領域の高さは端末とナビゲーション方式で違うので、実際の値から求める。
     * 試作機（Redmi Note 15 5G・ジェスチャナビ）では上 162px・下 135px（§7.3.9.1）。
     * 取れなければ従来の 8%。
     */
    fun dragStartRange(context: Context): ClosedFloatingPointRange<Float> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return DragLane.FALLBACK_START_RANGE
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = wm.currentWindowMetrics
        val height = metrics.bounds.height().toFloat()
        if (height <= 0f) return DragLane.FALLBACK_START_RANGE
        val gestures = metrics.windowInsets.getInsets(WindowInsets.Type.systemGestures())
        if (gestures.top == 0 && gestures.bottom == 0) return DragLane.FALLBACK_START_RANGE
        val margin = START_MARGIN_DP * context.resources.displayMetrics.density
        val top = (gestures.top + margin) / height
        val bottom = 1f - (gestures.bottom + margin) / height
        return if (top < bottom) top..bottom else DragLane.FALLBACK_START_RANGE
    }

    /** システムジェスチャ領域の境目から、さらに離す距離。境目ちょうどは判定が揺れうる。 */
    private const val START_MARGIN_DP = 4f
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
     * @param onDone 完了・取り消し・発行失敗のいずれでも 1 回だけ呼ぶ。調整中の「試す」で、
     *   重ね表示をタッチを受け付ける状態に戻すのに使う（design-pageturn.md §7.3.6）。
     * @return 発行または順番待ちに入れたら true。実際に届いたかは非同期で
     *   [GestureResultCallback] に返るため、ここでの true は「発行できた」ことのみを意味する。
     */
    fun tap(ratioX: Float, ratioY: Float, onDone: (() -> Unit)? = null): Boolean = submit("tap", onDone) {
        val size = screenSize() ?: return@submit false
        // 端ぴったりの座標はウィンドウ外と判定されうるため 1px 内側に丸める。
        val x = (ratioX.coerceIn(0f, 1f) * size.x).coerceIn(0f, size.x - 1f)
        val y = (ratioY.coerceIn(0f, 1f) * size.y).coerceIn(0f, size.y - 1f)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        dispatch(stroke, "tap at ($x, $y)", onDone) { finish(onDone) }
    }

    /**
     * 画面比率で指定した 2 点間をドラッグする。
     *
     * 動かしたまま離すと慣性スクロール（フリング）になるため、移動の後に
     * [DragGeometry.HOLD_MS] だけ静止してから離す。静止は
     * [GestureDescription.StrokeDescription.continueStroke] で第 2 ストロークとして出す。
     * 試作機では第 1・第 2 ストロークの隙間は 1〜6ms で、指が離れた扱いにはならなかった。
     *
     * 端よけは始点だけにかける。端から指を置くとシステムジェスチャ（ホーム・戻る・通知）になるが、
     * 置いた後の動きは最初に触れたウィンドウに届き続けるので、終点は画面の端まで置ける
     * （design-pageturn.md §7.3.9.1 で確認）。
     */
    fun drag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        onDone: (() -> Unit)? = null,
    ): Boolean = submit("drag", onDone) {
        val size = screenSize() ?: return@submit false
        val startRange = ScreenMetrics.dragStartRange(service)
        val x0 = fromX.coerceIn(DragLane.X_MIN, DragLane.X_MAX) * size.x
        val y0 = fromY.coerceIn(startRange.start, startRange.endInclusive) * size.y
        val x1 = toX.coerceIn(DragLane.X_MIN, DragLane.X_MAX) * size.x
        // 端ぴったりの座標はウィンドウ外と判定されうるため 1px 内側に丸める。
        val y1 = (toY.coerceIn(0f, 1f) * size.y).coerceIn(0f, size.y - 1f)

        val move = Path().apply {
            moveTo(x0, y0)
            lineTo(x1, y1)
        }
        val first = GestureDescription.StrokeDescription(move, 0L, DragGeometry.MOVE_MS, true)
        dispatch(first, "drag ($x0, $y0) -> ($x1, $y1)", onDone) {
            val stay = Path().apply { moveTo(x1, y1) }
            val second = first.continueStroke(stay, 0L, DragGeometry.HOLD_MS, false)
            if (!dispatch(second, "drag hold", onDone) { finish(onDone) }) finish(onDone)
        }
    }

    /** 順番待ちを捨てる。長押しで音量操作が始まったら、溜まっていた送りは不要。 */
    fun dropPending() = queue.dropPending()

    fun reset() = queue.reset()

    private fun submit(label: String, onDone: (() -> Unit)?, start: () -> Boolean): Boolean {
        val accepted = queue.submit {
            val started = start()
            // 発行できなかったときは完了の通知が来ないので、ここで知らせる。
            if (!started) onDone?.invoke()
            started
        }
        if (!accepted) {
            Log.i(TAG, "$label dropped")
            onDone?.invoke()
        }
        return accepted
    }

    private fun finish(onDone: (() -> Unit)?) {
        onDone?.invoke()
        queue.finish()
    }

    /**
     * ストロークを 1 本発行する。[onCompleted] は完了時だけ呼ぶ。取り消し時は
     * 順番待ちを進めるだけにする（ドラッグの第 2 ストロークを出さない）。
     */
    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        label: String,
        onDone: (() -> Unit)? = null,
        onCompleted: () -> Unit,
    ): Boolean {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onCompleted()

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // FLAG_SECURE の画面やシステム UI 上、利用者が画面に触れたときはここに来る。
                Log.i(TAG, "cancelled: $label")
                finish(onDone)
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

    }
}
