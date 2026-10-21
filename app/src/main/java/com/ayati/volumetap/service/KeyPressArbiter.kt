package com.ayati.volumetap.service

/** 対象とするボリュームキー。 */
enum class VolumeKey {
    UP,
    DOWN,
    ;

    val opposite: VolumeKey get() = if (this == UP) DOWN else UP
}

/**
 * 遅延実行の抽象。実機では [android.os.Handler]、テストでは仮想時間の Fake を渡す。
 * 同じ token での [postDelayed] は既存の予約を置き換える。
 */
interface Scheduler {
    fun postDelayed(token: Any, delayMs: Long, action: () -> Unit)
    fun cancel(token: Any)
}

/** 状態機械が外界に及ぼす作用。[VolumeTapService] が実装する。 */
interface ArbiterHost {
    /** そのキーに介入すべきか。false なら OS に素通しする（設計書 §5.1 の「介入しない条件」）。 */
    fun shouldIntercept(key: VolumeKey): Boolean

    /** 長押しと判定するまでの時間。設定変更を即時反映するため毎回問い合わせる。 */
    fun longPressMs(): Long

    /** 短押し確定。 */
    fun onShortPress(key: VolumeKey)

    /** 長押し中の音量 1 刻み。長押し確定時と、以降 [VOLUME_REPEAT_MS] ごとに呼ばれる。 */
    fun onVolumeStep(key: VolumeKey)

    /** 上下の同時押しを一時停止の切り替えに使うか（design-key-options.md §2.4）。 */
    fun chordEnabled(): Boolean

    /** 上下の同時押しを判定した（design-key-options.md §2.1）。 */
    fun onChord()
}

/**
 * ボリュームキーの短押し / 長押しを判定する状態機械（設計書 §5.1）。
 *
 * Android に依存しないため単体テスト可能。ここが本アプリで最もバグりやすい箇所なので、
 * タイミング境界は [KeyPressArbiterTest] で担保する。
 *
 * すべてのメソッドはメインスレッドからのみ呼ばれる前提で、排他は行わない。
 */
class KeyPressArbiter(
    private val host: ArbiterHost,
    private val scheduler: Scheduler,
) {
    enum class State {
        /** 何も押されていない。 */
        IDLE,

        /** 介入しないと判断したキーが押されている。対応する UP も素通しする。 */
        PASSTHROUGH,

        /** 押下中。まだ短押しか長押しか決まっていない。 */
        PENDING,

        /** 長押し確定。音量を刻んでいる。 */
        LONG_PRESS,

        /** 上下の同時押しの一方。リピートは消費し、UP は [KeyState.passUp] でなければ消費する。 */
        CHORD,
    }

    private class KeyState {
        var state = State.IDLE

        /** [State.CHORD] の UP を OS に渡すか。DOWN を OS に渡していたキーは UP も渡す。 */
        var passUp = false
        val longPressToken = Any()
        val repeatToken = Any()
        val timeoutToken = Any()
    }

    private val states = VolumeKey.entries.associateWith { KeyState() }

    fun stateOf(key: VolumeKey): State = states.getValue(key).state

    /**
     * @param repeatCount [android.view.KeyEvent.getRepeatCount]。OS のオートリピートは
     *   自前タイマーと二重に判定してしまうため、押下中はすべて消費して無視する。
     * @return true なら消費（OS に渡さない）。
     */
    fun onKeyDown(key: VolumeKey, repeatCount: Int): Boolean {
        val s = states.getValue(key)
        return when (s.state) {
            State.IDLE -> {
                val other = states.getValue(key.opposite)
                if (repeatCount == 0 && other.state == State.CHORD) {
                    // 同時押しのまま片方だけ押し直した。切り替えは繰り返さない。
                    holdChord(s, passUp = false)
                    true
                } else if (repeatCount == 0 && other.state != State.IDLE && host.chordEnabled()) {
                    enterChord(s, other)
                    true
                } else if (repeatCount > 0) {
                    // DOWN を取りこぼした状態でのリピート。素通しに倒す。
                    s.state = State.PASSTHROUGH
                    false
                } else if (!host.shouldIntercept(key)) {
                    s.state = State.PASSTHROUGH
                    false
                } else {
                    s.state = State.PENDING
                    scheduler.postDelayed(s.longPressToken, host.longPressMs()) {
                        enterLongPress(key)
                    }
                    true
                }
            }

            State.PASSTHROUGH -> false
            State.PENDING, State.LONG_PRESS, State.CHORD -> true
        }
    }

    /** @return true なら消費（OS に渡さない）。 */
    fun onKeyUp(key: VolumeKey): Boolean {
        val s = states.getValue(key)
        return when (s.state) {
            // DOWN を見ていない UP。介入しない。
            State.IDLE -> false

            State.PASSTHROUGH -> {
                s.state = State.IDLE
                false
            }

            State.PENDING -> {
                scheduler.cancel(s.longPressToken)
                s.state = State.IDLE
                host.onShortPress(key)
                true
            }

            State.LONG_PRESS -> {
                clearTimers(s)
                s.state = State.IDLE
                true
            }

            State.CHORD -> {
                clearTimers(s)
                s.state = State.IDLE
                !s.passUp
            }
        }
    }

    /** サービス停止時などの後始末。予約済みタイマーをすべて取り消す。 */
    fun reset() {
        for (s in states.values) {
            clearTimers(s)
            scheduler.cancel(s.longPressToken)
            s.state = State.IDLE
            s.passUp = false
        }
    }

    /**
     * 先に押されていた [first] を離す前に [second] が押された（design-key-options.md §2.1）。
     * 先のキーの短押し・長押しは取り消し、押した時点で切り替える。
     */
    private fun enterChord(second: KeyState, first: KeyState) {
        scheduler.cancel(first.longPressToken)
        clearTimers(first)
        holdChord(first, passUp = first.state == State.PASSTHROUGH)
        holdChord(second, passUp = false)
        host.onChord()
    }

    private fun holdChord(s: KeyState, passUp: Boolean) {
        s.state = State.CHORD
        s.passUp = passUp
        // UP が届かないまま画面が切り替わるケースの保険（LONG_PRESS と同じ）。
        scheduler.postDelayed(s.timeoutToken, LONG_PRESS_TIMEOUT_MS) {
            s.state = State.IDLE
            s.passUp = false
        }
    }

    private fun enterLongPress(key: VolumeKey) {
        val s = states.getValue(key)
        if (s.state != State.PENDING) return
        s.state = State.LONG_PRESS
        host.onVolumeStep(key)
        scheduleRepeat(key)
        // UP が届かないまま画面が切り替わるケースの保険（設計書 §5.1）。
        scheduler.postDelayed(s.timeoutToken, LONG_PRESS_TIMEOUT_MS) {
            clearTimers(s)
            s.state = State.IDLE
        }
    }

    private fun scheduleRepeat(key: VolumeKey) {
        val s = states.getValue(key)
        scheduler.postDelayed(s.repeatToken, VOLUME_REPEAT_MS) {
            if (s.state != State.LONG_PRESS) return@postDelayed
            host.onVolumeStep(key)
            scheduleRepeat(key)
        }
    }

    private fun clearTimers(s: KeyState) {
        scheduler.cancel(s.repeatToken)
        scheduler.cancel(s.timeoutToken)
    }

    companion object {
        /** 長押し中の音量刻み間隔。 */
        const val VOLUME_REPEAT_MS = 120L

        /** LONG_PRESS・CHORD 状態の上限。超過したら強制的に IDLE へ戻す。 */
        const val LONG_PRESS_TIMEOUT_MS = 10_000L
    }
}
