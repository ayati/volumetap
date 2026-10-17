package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.ScreenOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** 調整中にどちらのドラッグを動かしているか。 */
enum class DragDirection { FORWARD, BACKWARD }

/**
 * 設定画面からサービスへの調整の依頼（design-pageturn.md §7.3.6）。
 *
 * 設定画面とサービスは同じプロセスなので、ブロードキャストや IPC を使わずにここで受け渡す。
 * サービスが動いていないときの依頼は、次に動き出したときに拾われないよう、設定画面の側で
 * ユーザー補助が有効なときだけ出す。
 */
object CalibrationRequests {

    data class Request(val packageName: String, val direction: DragDirection)

    private val pending = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = pending.asStateFlow()

    fun request(packageName: String, direction: DragDirection) {
        pending.value = Request(packageName, direction)
    }

    /** 依頼を取り出す。同じ依頼を 2 度処理しないよう、取り出したら空にする。 */
    fun take(): Request? = pending.getAndUpdate { null }
}

/**
 * 対象アプリの上での調整の状態（design-pageturn.md §7.3.6）。Android に依存しない純ロジック。
 *
 * ```
 *          start                      begin
 *   IDLE ─────────▶ WAITING ─────────────────▶ ADJUSTING
 *    ▲                 │                           │
 *    └─ close（保存・やめる・対象アプリから離れた・向きが変わった・画面が消えた）
 * ```
 *
 * WAITING は「対象アプリを普通に操作して、調整したい画面まで進めてもらう」段階。
 * 重ね表示は小さな帯だけ。ADJUSTING で全面の重ね表示に切り替える。
 */
class CalibrationSession(private val ownPackage: String) {

    enum class Phase { IDLE, WAITING, ADJUSTING }

    var phase: Phase = Phase.IDLE
        private set

    /** 調整しているアプリ。IDLE のときは null。 */
    var target: String? = null
        private set

    var direction: DragDirection = DragDirection.FORWARD
        private set

    /** 調整を始めたときの画面の向き。ADJUSTING のときだけ値がある。 */
    var orientation: ScreenOrientation? = null
        private set

    /** 調整中の値。ADJUSTING のときだけ値がある。 */
    var lane: DragLane? = null
        private set

    /** その向きを調整していないときに使われる値。「既定に戻す」で戻す先。 */
    private var fallback: DragLane? = null

    /**
     * 対象アプリが一度でも前面に来たか。起動を待つあいだは、設定画面（自アプリ）が
     * 前面のままでも閉じない。
     */
    var seenTarget: Boolean = false
        private set

    val isActive: Boolean get() = phase != Phase.IDLE

    fun start(packageName: String, direction: DragDirection) {
        close()
        phase = Phase.WAITING
        target = packageName
        this.direction = direction
    }

    /**
     * 前面アプリが変わった。
     *
     * @return 対象アプリから離れたので閉じたら true。
     */
    fun onForeground(packageName: String?): Boolean {
        if (!isActive) return false
        if (packageName == target) {
            seenTarget = true
            return false
        }
        // 起動を待つあいだ（設定画面が前面のまま、など）は閉じない。
        if (!seenTarget) return false
        close()
        return true
    }

    /**
     * 自アプリのウィンドウのイベントのうち、重ね表示のもの（前面アプリの変化として扱わないもの）か。
     *
     * 重ね表示を出すと、自パッケージでウィンドウのイベントが届き、前面アプリが上書きされる
     * （§7.3.9.1 で確認）。上書きされると音量キーが素通しになり、調整も閉じてしまう。
     * 一方、利用者が設定画面に戻ったときのイベントは、対象アプリから離れたものとして扱う。
     * 画面（Activity）はすべて `ui` パッケージにあるので、クラス名で見分ける。
     */
    fun isOwnOverlayEvent(packageName: String?, className: String?): Boolean =
        isActive && packageName == ownPackage && className?.startsWith("$ownPackage.ui.") != true

    /** 「調整を始める」。その向きの現在の値から始める。 */
    fun begin(orientation: ScreenOrientation, profile: AppProfile): Boolean {
        if (phase != Phase.WAITING || !seenTarget) return false
        phase = Phase.ADJUSTING
        this.orientation = orientation
        lane = profile.dragLaneFor(orientation)
        fallback = profile.fallbackDragLane(orientation)
        return true
    }

    /**
     * 画面の向きが変わった。調整中なら閉じる（比率の基準が変わるため）。
     *
     * @return 閉じたら true。
     */
    fun onOrientation(orientation: ScreenOrientation): Boolean {
        if (phase != Phase.ADJUSTING || orientation == this.orientation) return false
        close()
        return true
    }

    fun updateLane(value: DragLane) {
        if (phase == Phase.ADJUSTING) lane = value
    }

    fun selectDirection(value: DragDirection) {
        direction = value
    }

    /** 「既定に戻す」。その向きを調整していないときの値に戻す（保存すると未調整に戻る）。 */
    fun resetToFallback() {
        if (phase == Phase.ADJUSTING) lane = fallback
    }

    /**
     * 保存する値。調整していないときの値と同じなら null（未調整として保存する）。
     * 横持ちの「未調整」は「縦持ちと同じ」を意味するので、値をそのまま書くと
     * 縦持ちを後で調整したときに追従しなくなる。
     */
    fun laneToSave(): DragLane? = lane?.takeIf { it != fallback }

    /** 音量キーで試すときに、保存値の代わりに使う値。 */
    fun override(): CalibrationOverride? {
        val pkg = target ?: return null
        val o = orientation ?: return null
        val l = lane ?: return null
        return if (phase == Phase.ADJUSTING) CalibrationOverride(pkg, o, l) else null
    }

    fun close() {
        phase = Phase.IDLE
        target = null
        orientation = null
        lane = null
        fallback = null
        seenTarget = false
        direction = DragDirection.FORWARD
    }
}
