package com.ayati.volumetap.data

/** ボリュームキー短押しに割り当てる動作（設計書 §3.1）。 */
enum class KeyAction {
    /** 本来の音量調整。キーを横取りせず OS に素通しする。 */
    NORMAL,

    /** 登録済み座標を 1 回タップする。 */
    TAP,
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
 * アプリ設定（設計書 §5.3）。
 *
 * 既定値は両キーとも [KeyAction.NORMAL]。インストール直後・ユーザー補助を有効にした
 * 直後は端末の挙動が一切変わらない。
 */
data class AppSettings(
    val volumeUpAction: KeyAction = KeyAction.NORMAL,
    val volumeDownAction: KeyAction = KeyAction.NORMAL,
    val tapPointUpPortrait: TapPoint? = null,
    val tapPointUpLandscape: TapPoint? = null,
    val tapPointDownPortrait: TapPoint? = null,
    val tapPointDownLandscape: TapPoint? = null,
    val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
    val hapticFeedback: Boolean = true,
    val enabledPackages: Set<String> = emptySet(),
    /** クイック設定タイルからの一時停止。true のあいだは一切介入しない。 */
    val paused: Boolean = false,
) {
    fun actionFor(key: com.ayati.volumetap.service.VolumeKey): KeyAction = when (key) {
        com.ayati.volumetap.service.VolumeKey.UP -> volumeUpAction
        com.ayati.volumetap.service.VolumeKey.DOWN -> volumeDownAction
    }

    fun tapPointFor(
        key: com.ayati.volumetap.service.VolumeKey,
        orientation: ScreenOrientation,
    ): TapPoint? {
        val (portrait, landscape) = when (key) {
            com.ayati.volumetap.service.VolumeKey.UP ->
                tapPointUpPortrait to tapPointUpLandscape
            com.ayati.volumetap.service.VolumeKey.DOWN ->
                tapPointDownPortrait to tapPointDownLandscape
        }
        return when (orientation) {
            ScreenOrientation.PORTRAIT -> portrait
            // 横持ちの座標が未登録なら縦の比率を流用する（設計書 §3.2）。
            ScreenOrientation.LANDSCAPE -> landscape ?: portrait
        }
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 300
        val LONG_PRESS_CHOICES = listOf(200, 300, 400, 500)
    }
}
