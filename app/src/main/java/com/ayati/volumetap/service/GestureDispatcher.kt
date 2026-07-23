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
    }
}
