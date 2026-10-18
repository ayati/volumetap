package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.TapPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** 調整中にどちらのドラッグを動かしているか。 */
enum class DragDirection { FORWARD, BACKWARD }

/** 何を調整しているか（design-pageturn.md §7.3.7.1）。 */
sealed interface CalibrationKind {
    /** ドラッグの位置と送り量。[direction] は最初に選んでおく向き。 */
    data class Drag(val direction: DragDirection) : CalibrationKind

    /** [key] でタップする位置。タップ座標はキーごとに持つので、1 つずつ登録する。 */
    data class Tap(val key: VolumeKey) : CalibrationKind
}

/**
 * 設定画面からサービスへの調整の依頼（design-pageturn.md §7.3.6）。
 *
 * 設定画面とサービスは同じプロセスなので、ブロードキャストや IPC を使わずにここで受け渡す。
 * サービスが動いていないときの依頼は、次に動き出したときに拾われないよう、設定画面の側で
 * ユーザー補助が有効なときだけ出す。
 */
object CalibrationRequests {

    data class Request(val packageName: String, val kind: CalibrationKind)

    private val pending = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = pending.asStateFlow()

    fun request(packageName: String, kind: CalibrationKind) {
        pending.value = Request(packageName, kind)
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
 *
 * ドラッグの調整（2a）とタップ位置の登録（2b、§7.3.7.1）で、状態の移り方は同じ。
 * 違うのは、ADJUSTING で動かす値（[lane] か [point]）だけ。
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

    /** タップ位置を登録しているキー。ドラッグの調整のときは null。 */
    var tapKey: VolumeKey? = null
        private set

    val isTap: Boolean get() = tapKey != null

    /** 調整を始めたときの画面の向き。ADJUSTING のときだけ値がある。 */
    var orientation: ScreenOrientation? = null
        private set

    /** 調整中の値。ADJUSTING のときだけ値がある。 */
    var lane: DragLane? = null
        private set

    /** その向きを調整していないときに使われる値。「既定に戻す」で戻す先。 */
    private var fallback: DragLane? = null

    /** 登録中のタップ位置。ADJUSTING で、しるしを置いたか登録済みのときだけ値がある。 */
    var point: TapPoint? = null
        private set

    /** その向きのタップ位置が未登録のときに使われる値（横持ちなら縦の比率）。 */
    private var pointFallback: TapPoint? = null

    /**
     * 対象アプリが一度でも前面に来たか。起動を待つあいだは、設定画面（自アプリ）が
     * 前面のままでも閉じない。
     */
    var seenTarget: Boolean = false
        private set

    val isActive: Boolean get() = phase != Phase.IDLE

    fun start(packageName: String, kind: CalibrationKind) {
        close()
        phase = Phase.WAITING
        target = packageName
        when (kind) {
            is CalibrationKind.Drag -> direction = kind.direction
            is CalibrationKind.Tap -> tapKey = kind.key
        }
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

    /** 「調整を始める」。その向きで、いま実際に使われている値から始める。 */
    fun begin(orientation: ScreenOrientation, profile: AppProfile): Boolean {
        if (phase != Phase.WAITING || !seenTarget) return false
        phase = Phase.ADJUSTING
        this.orientation = orientation
        val key = tapKey
        if (key == null) {
            lane = profile.dragLaneFor(orientation)
            fallback = profile.fallbackDragLane(orientation)
        } else {
            val setting = profile.settingFor(key)
            // 横持ちが未登録なら縦の比率が使われている。向きは登録先に揃える。
            point = setting.tapPointFor(orientation)?.copy(orientation = orientation)
            pointFallback = when (orientation) {
                ScreenOrientation.PORTRAIT -> null
                ScreenOrientation.LANDSCAPE -> setting.tapPortrait?.copy(orientation = orientation)
            }
        }
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
        if (phase == Phase.ADJUSTING && !isTap) lane = value
    }

    /** しるしを置いた・動かした。座標は画面に対する比率。 */
    fun updatePoint(x: Float, y: Float) {
        val o = orientation ?: return
        if (phase == Phase.ADJUSTING && isTap) {
            point = TapPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), o)
        }
    }

    /** 保存できるか。タップは、しるしを置くまで保存できない。 */
    val canSave: Boolean
        get() = phase == Phase.ADJUSTING && (!isTap || point != null)

    fun selectDirection(value: DragDirection) {
        direction = value
    }

    /** 「既定に戻す」。その向きを調整していないときの値に戻す（保存すると未調整に戻る）。 */
    fun resetToFallback() {
        if (phase == Phase.ADJUSTING && !isTap) lane = fallback
    }

    /**
     * 保存する値。調整していないときの値と同じなら null（未調整として保存する）。
     * 横持ちの「未調整」は「縦持ちと同じ」を意味するので、値をそのまま書くと
     * 縦持ちを後で調整したときに追従しなくなる。
     */
    fun laneToSave(): DragLane? = lane?.takeIf { it != fallback }

    /**
     * 保存する内容を、プロファイルへの変更として返す。保存できないときは null。
     *
     * 保存は非同期に行い、その前に [close] で状態を消すので、値はここで取り出しておく。
     * タップは、未登録のときの値（横持ちなら縦の比率）から動かしていなければ未登録のまま
     * にする。理由は [laneToSave] と同じ（§7.3.7.1）。
     */
    fun saveChange(): ((AppProfile) -> AppProfile)? {
        if (!canSave) return null
        val o = orientation ?: return null
        val key = tapKey
        if (key == null) {
            val l = laneToSave()
            return { it.withDragLane(o, l) }
        }
        val p = point ?: return null
        return if (p == pointFallback) {
            { profile -> profile.with(key) { it.withoutTapPoint(o) } }
        } else {
            { profile -> profile.with(key) { it.withTapPoint(p) } }
        }
    }

    /** 音量キーで試すときに、保存値の代わりに使う値。 */
    fun override(): CalibrationOverride? {
        if (phase != Phase.ADJUSTING) return null
        val pkg = target ?: return null
        val o = orientation ?: return null
        val key = tapKey
        return if (key == null) {
            lane?.let { CalibrationOverride.Lane(pkg, o, it) }
        } else {
            point?.let { CalibrationOverride.Tap(pkg, o, key, it.ratioX, it.ratioY) }
        }
    }

    fun close() {
        phase = Phase.IDLE
        target = null
        orientation = null
        lane = null
        fallback = null
        point = null
        pointFallback = null
        tapKey = null
        seenTarget = false
        direction = DragDirection.FORWARD
    }
}
