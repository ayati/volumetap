package com.ayati.volumetap.service

import android.content.Intent

/**
 * Phase 0 の実機スパイク用の設定（design-pageturn.md §12.1）。デバッグビルドでのみ使う。
 *
 * 有効にすると、タップの設定に関係なく音量−＝ドラッグで送る、音量＋＝ドラッグで戻すになる。
 * 一時停止・自アプリ・アプリ絞り込みの判定は通常どおり効く。
 * 値は adb からのブロードキャストで変える（ビルドし直さずに調整するため）。
 *
 * ```
 * adb shell am broadcast -a com.ayati.volumetap.debug.DRAG -p com.ayati.volumetap \
 *     --ez on true --ef ffrom 0.80 --ef fto 0.17 --ei drag 250 --ei hold 120
 * adb shell am broadcast -a com.ayati.volumetap.debug.DRAG -p com.ayati.volumetap --es fire forward
 * ```
 *
 * 状態はプロセス内にだけ持つ。サービスが再起動したら無効に戻る。
 */
object DragSpike {
    const val ACTION = "com.ayati.volumetap.debug.DRAG"

    var enabled = false
    var x = 0.5f
    var forwardFrom = 0.80f
    var forwardTo = 0.17f

    // 戻しの始点は上に寄せすぎない。X は上へ戻すとヘッダーと「新しいポスト」ボタンが現れ、
    // 0.20 だとそのボタンの上から始まってスクロールしなくなった（design-pageturn.md §5.2）。
    var backFrom = 0.28f
    var backTo = 0.91f
    var dragMs = 250L

    /** 0 なら止めずに離す（フリングが出ることの確認用）。 */
    var holdMs = 120L

    /** ブロードキャストの extras を反映する。指定の無い値は変えない。 */
    fun apply(intent: Intent) {
        if (intent.hasExtra("on")) enabled = intent.getBooleanExtra("on", enabled)
        if (intent.hasExtra("x")) x = intent.getFloatExtra("x", x)
        if (intent.hasExtra("ffrom")) forwardFrom = intent.getFloatExtra("ffrom", forwardFrom)
        if (intent.hasExtra("fto")) forwardTo = intent.getFloatExtra("fto", forwardTo)
        if (intent.hasExtra("bfrom")) backFrom = intent.getFloatExtra("bfrom", backFrom)
        if (intent.hasExtra("bto")) backTo = intent.getFloatExtra("bto", backTo)
        if (intent.hasExtra("drag")) dragMs = intent.getIntExtra("drag", dragMs.toInt()).toLong()
        if (intent.hasExtra("hold")) holdMs = intent.getIntExtra("hold", holdMs.toInt()).toLong()
    }

    fun drag(gestures: GestureDispatcher, key: VolumeKey): Boolean = when (key) {
        VolumeKey.DOWN -> gestures.drag(x, forwardFrom, x, forwardTo, dragMs, holdMs)
        VolumeKey.UP -> gestures.drag(x, backFrom, x, backTo, dragMs, holdMs)
    }

    fun describe(): String =
        "spike enabled=$enabled x=$x forward=$forwardFrom->$forwardTo " +
            "back=$backFrom->$backTo drag=${dragMs}ms hold=${holdMs}ms"
}
