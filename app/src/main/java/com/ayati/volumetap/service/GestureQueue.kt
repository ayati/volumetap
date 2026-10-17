package com.ayati.volumetap.service

/**
 * 注入するジェスチャの順番待ち（design-pageturn.md §5.3）。
 *
 * 新しい `dispatchGesture` は実行中のジェスチャを取り消すため、取り消されたドラッグは
 * 途中までしか進まず送り量が狂う。そこで実行中は次の 1 件だけを待たせ、
 * それ以上は捨てる（押しっぱなしに近い速さの連打を何画面分も溜め込まない）。
 *
 * Android に依存しない純ロジック。すべてメインスレッドから呼ぶ前提で排他はしない。
 */
class GestureQueue {

    private var running = false
    private var pending: (() -> Boolean)? = null

    val isRunning: Boolean get() = running

    /**
     * ジェスチャを実行する。実行中なら待たせる。
     *
     * @param start ジェスチャを発行し、発行できたら true を返す。発行できたら、
     *   完了・取り消しのどちらでも必ず [finish] を呼ぶこと。
     * @return 発行したか待たせたなら true、捨てたか発行に失敗したなら false。
     */
    fun submit(start: () -> Boolean): Boolean {
        if (running) {
            if (pending != null) return false
            pending = start
            return true
        }
        running = start()
        return running
    }

    /** 実行中のジェスチャが終わった。待っているものがあれば続けて発行する。 */
    fun finish() {
        running = false
        while (true) {
            val next = pending ?: return
            pending = null
            running = next()
            // 発行に失敗したら次へ進む（待ちは高々 1 件なので、実際には 1 回で抜ける）。
            if (running) return
        }
    }

    /** 待っているものを捨てる。長押しで音量操作が始まったときなど。 */
    fun dropPending() {
        pending = null
    }

    /** すべて忘れる。サービス停止時。 */
    fun reset() {
        running = false
        pending = null
    }
}
