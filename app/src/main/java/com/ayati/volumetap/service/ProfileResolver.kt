package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.ScreenOrientation

/** 短押しで実際に注入する操作。座標はすべて画面に対する比率。 */
sealed interface ResolvedAction {
    data class Tap(val x: Float, val y: Float) : ResolvedAction

    data class Drag(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) :
        ResolvedAction
}

/**
 * ドラッグの形（design-pageturn.md §5.1・§5.2）。Redmi Note 15 5G での試作で決めた値。
 *
 * 送りと戻しの移動量は必ず揃える（どちらも画面高の 63%）。揃っていないと、
 * 送って戻すたびに位置がずれていく。
 */
object DragGeometry {
    const val X = 0.5f

    const val FORWARD_FROM = 0.80f
    const val FORWARD_TO = 0.17f

    /**
     * 戻しの始点は上に寄せすぎない。X は上へ戻すとヘッダーと「新しいポスト」ボタンが現れ、
     * 0.20 ではそのボタンの上から始まって 2 回目以降の戻しが効かなくなった。
     */
    const val BACKWARD_FROM = 0.28f
    const val BACKWARD_TO = 0.91f

    /** 移動にかける時間。短すぎると 1 回の移動イベントが大きくなり取りこぼすアプリがある。 */
    const val MOVE_MS = 250L

    /**
     * 移動の後に止まっている時間。速度判定の窓（約 100ms）を超えて静止させ、
     * 離したときの慣性スクロール（フリング）を殺す。0 にすると Chrome で 1 画面以上滑った。
     */
    const val HOLD_MS = 120L
}

/**
 * 前面アプリとキーから、注入する操作を決める（design-pageturn.md §6.2）。
 *
 * Android に依存しない純ロジック。`null` は「介入しない（音量に素通し）」を意味する。
 * **どの送り方式でも、実行できないときは素通しする。**「割り当てたのに何も起きず、
 * 音量も効かない」状態を構造的に作らないため。
 */
object ProfileResolver {

    fun resolve(
        settings: AppSettings,
        foreground: String?,
        ownPackage: String,
        key: VolumeKey,
        orientation: ScreenOrientation,
    ): ResolvedAction? {
        // クイック設定タイルからの一時停止。最優先で素通しする。
        if (settings.paused) return null
        // 自アプリの設定画面・座標登録画面では介入しない。
        if (foreground == ownPackage) return null

        val setting = settings.profileFor(foreground).settingFor(key)
        return when (setting.action) {
            KeyAction.NORMAL -> null
            // 座標が未登録なら素通し（設計書 §5.1 条件 3）。
            KeyAction.TAP -> setting.tapPointFor(orientation)?.let {
                ResolvedAction.Tap(it.ratioX, it.ratioY)
            }
            KeyAction.DRAG_FORWARD -> ResolvedAction.Drag(
                DragGeometry.X, DragGeometry.FORWARD_FROM,
                DragGeometry.X, DragGeometry.FORWARD_TO,
            )
            KeyAction.DRAG_BACKWARD -> ResolvedAction.Drag(
                DragGeometry.X, DragGeometry.BACKWARD_FROM,
                DragGeometry.X, DragGeometry.BACKWARD_TO,
            )
        }
    }
}
