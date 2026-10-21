package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.ScreenOrientation

/** 短押しで実際に注入する操作。座標はすべて画面に対する比率。 */
sealed interface ResolvedAction {
    data class Tap(val x: Float, val y: Float) : ResolvedAction

    data class Drag(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) :
        ResolvedAction
}

/**
 * ドラッグの時間（design-pageturn.md §5.1）。Redmi Note 15 5G での試作で決めた値。
 * 位置と送り量はアプリごとに調整できる（[DragLane]、§7.3）が、時間は利用者に見せない。
 */
object DragGeometry {
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

    /**
     * @param calibration 調整中の値（§7.3.6、§7.3.7.1）。前面が調整中のアプリで、向きも同じときだけ、
     *   保存値の代わりに使う。タップの上書きは、さらにキーも同じときだけ。
     * @param foregroundHost 前面が PWA のとき、それを動かしているブラウザ。PWA に割り当てが無ければ
     *   ブラウザの割り当てを使う（design-pwa-picker.md §2.4）。
     */
    fun resolve(
        settings: AppSettings,
        foreground: String?,
        ownPackage: String,
        key: VolumeKey,
        orientation: ScreenOrientation,
        calibration: CalibrationOverride? = null,
        foregroundHost: String? = null,
    ): ResolvedAction? {
        // クイック設定タイルからの一時停止。最優先で素通しする。
        if (settings.paused) return null
        // 自アプリの設定画面・座標登録画面では介入しない。
        if (foreground == ownPackage) return null

        val profile = settings.profileFor(foreground, foregroundHost)
        // 上下の入れ替え（design-key-options.md §1.1）。以下は押したキーではなく欄で考える。
        val slot = settings.slotFor(key)
        val setting = profile.settingFor(slot)
        val active = calibration?.takeIf { it.packageName == foreground && it.orientation == orientation }
        val lane = (active as? CalibrationOverride.Lane)?.lane ?: profile.dragLaneFor(orientation)
        return when (setting.action) {
            KeyAction.NORMAL -> null
            KeyAction.TAP -> (active as? CalibrationOverride.Tap)?.takeIf { it.key == slot }
                ?.let { ResolvedAction.Tap(it.x, it.y) }
                // 座標が未登録なら素通し（設計書 §5.1 条件 3）。
                ?: setting.tapPointFor(orientation)?.let { ResolvedAction.Tap(it.ratioX, it.ratioY) }
            KeyAction.DRAG_FORWARD -> ResolvedAction.Drag(lane.x, lane.forwardFrom, lane.x, lane.forwardTo)
            KeyAction.DRAG_BACKWARD -> ResolvedAction.Drag(lane.x, lane.backwardFrom, lane.x, lane.backwardTo)
        }
    }
}

/** 調整中の値（design-pageturn.md §7.3.6、§7.3.7.1）。 */
sealed interface CalibrationOverride {
    val packageName: String
    val orientation: ScreenOrientation

    /** 調整中のドラッグの形。 */
    data class Lane(
        override val packageName: String,
        override val orientation: ScreenOrientation,
        val lane: DragLane,
    ) : CalibrationOverride

    /** 登録中のタップ位置。[key] のタップにだけ効く。 */
    data class Tap(
        override val packageName: String,
        override val orientation: ScreenOrientation,
        val key: VolumeKey,
        val x: Float,
        val y: Float,
    ) : CalibrationOverride
}
