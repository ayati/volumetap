package com.ayati.volumetap.data

import com.ayati.volumetap.service.VolumeKey

/** ボリュームキー短押しに割り当てる送り方式（design-pageturn.md §6.1）。 */
enum class KeyAction {
    /** 本来の音量調整。キーを横取りせず OS に素通しする。 */
    NORMAL,

    /** 登録済み座標を 1 回タップする。 */
    TAP,

    /** 画面を下から上へドラッグし、次の 1 画面へ送る。 */
    DRAG_FORWARD,

    /** 画面を上から下へドラッグし、前の 1 画面へ戻す。 */
    DRAG_BACKWARD,
    ;

    val isDrag: Boolean get() = this == DRAG_FORWARD || this == DRAG_BACKWARD
}

enum class ScreenOrientation { PORTRAIT, LANDSCAPE }

/**
 * タップ位置。解像度・向きが変わっても追従するよう、画面サイズに対する比率で持つ
 * （設計書 §3.2）。
 */
data class TapPoint(
    val ratioX: Float,
    val ratioY: Float,
    val orientation: ScreenOrientation,
)

/**
 * 1 つのキーの割り当て。
 *
 * タップ座標は [action] がタップ以外でも保持する。ドラッグに切り替えてから
 * タップに戻したときに、登録し直さずに済むようにするため。
 */
data class KeySetting(
    val action: KeyAction = KeyAction.NORMAL,
    val tapPortrait: TapPoint? = null,
    val tapLandscape: TapPoint? = null,
) {
    fun tapPointFor(orientation: ScreenOrientation): TapPoint? = when (orientation) {
        ScreenOrientation.PORTRAIT -> tapPortrait
        // 横持ちの座標が未登録なら縦の比率を流用する（設計書 §3.2）。
        ScreenOrientation.LANDSCAPE -> tapLandscape ?: tapPortrait
    }

    fun withTapPoint(point: TapPoint): KeySetting = when (point.orientation) {
        ScreenOrientation.PORTRAIT -> copy(tapPortrait = point)
        ScreenOrientation.LANDSCAPE -> copy(tapLandscape = point)
    }

    fun withoutTapPoint(orientation: ScreenOrientation): KeySetting = when (orientation) {
        ScreenOrientation.PORTRAIT -> copy(tapPortrait = null)
        ScreenOrientation.LANDSCAPE -> copy(tapLandscape = null)
    }
}

/**
 * あるアプリでの音量＋／−の割り当て（design-pageturn.md §6.1）。
 *
 * ドラッグの形はキーごとではなくアプリごとに持つ。送り量を＋と−で共有するため（§7.3.3）。
 */
data class AppProfile(
    val volumeUp: KeySetting = KeySetting(),
    val volumeDown: KeySetting = KeySetting(),
    /** 縦持ちのドラッグの形。null なら既定値。 */
    val dragPortrait: DragLane? = null,
    /** 横持ちのドラッグの形。null なら縦持ちの値（それも無ければ既定値）。 */
    val dragLandscape: DragLane? = null,
) {
    fun dragLaneFor(orientation: ScreenOrientation): DragLane =
        dragLaneOrNull(orientation) ?: fallbackDragLane(orientation)

    /** その向きで調整した値。未調整なら null。 */
    fun dragLaneOrNull(orientation: ScreenOrientation): DragLane? = when (orientation) {
        ScreenOrientation.PORTRAIT -> dragPortrait
        ScreenOrientation.LANDSCAPE -> dragLandscape
    }

    /** その向きを調整していないときに使われる値（タップ座標と同じ規則）。 */
    fun fallbackDragLane(orientation: ScreenOrientation): DragLane = when (orientation) {
        ScreenOrientation.PORTRAIT -> DragLane.DEFAULT
        ScreenOrientation.LANDSCAPE -> dragPortrait ?: DragLane.DEFAULT
    }

    /** その向きの値を差し替える。null で未調整に戻す。 */
    fun withDragLane(orientation: ScreenOrientation, lane: DragLane?): AppProfile = when (orientation) {
        ScreenOrientation.PORTRAIT -> copy(dragPortrait = lane)
        ScreenOrientation.LANDSCAPE -> copy(dragLandscape = lane)
    }

    /** どちらかのキーがドラッグか。調整の欄を出すかどうかに使う。 */
    val usesDrag: Boolean
        get() = volumeUp.action.isDrag || volumeDown.action.isDrag

    fun settingFor(key: VolumeKey): KeySetting = when (key) {
        VolumeKey.UP -> volumeUp
        VolumeKey.DOWN -> volumeDown
    }

    fun with(key: VolumeKey, transform: (KeySetting) -> KeySetting): AppProfile = when (key) {
        VolumeKey.UP -> copy(volumeUp = transform(volumeUp))
        VolumeKey.DOWN -> copy(volumeDown = transform(volumeDown))
    }

    /** どちらのキーも横取りしないか。 */
    val isPassThrough: Boolean
        get() = volumeUp.action == KeyAction.NORMAL && volumeDown.action == KeyAction.NORMAL

    companion object {
        /** アプリを追加したときの初期値。主な用途である「−で送る／＋で戻す」にしておく。 */
        val NEW_APP = AppProfile(
            volumeUp = KeySetting(KeyAction.DRAG_BACKWARD),
            volumeDown = KeySetting(KeyAction.DRAG_FORWARD),
        )
    }
}

/**
 * アプリ設定（design-pageturn.md §6.1）。
 *
 * 前面アプリに [profiles] があればそれを、なければ [defaultProfile] を使う。
 * [defaultProfile] の既定値は両キーとも [KeyAction.NORMAL]。インストール直後・
 * ユーザー補助を有効にした直後は端末の挙動が一切変わらない。
 */
data class AppSettings(
    /** パッケージ名 → プロファイル。 */
    val profiles: Map<String, AppProfile> = emptyMap(),
    /** 個別のプロファイルを持たないアプリ（ホーム画面を含む）に使う割り当て。 */
    val defaultProfile: AppProfile = AppProfile(),
    val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
    val hapticFeedback: Boolean = true,
    /** クイック設定タイルからの一時停止。true のあいだは一切介入しない。 */
    val paused: Boolean = false,
) {
    fun profileFor(packageName: String?): AppProfile =
        packageName?.let { profiles[it] } ?: defaultProfile

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 300
        val LONG_PRESS_CHOICES = listOf(200, 300, 400, 500)
    }
}
