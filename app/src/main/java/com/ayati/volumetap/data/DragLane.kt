package com.ayati.volumetap.data

/**
 * アプリ・画面の向きごとのドラッグの形（design-pageturn.md §7.3.2）。座標はすべて画面に対する比率。
 *
 * **終点を持たず、送り量を 1 つだけ持つ。** 送りと戻しの量が揃っていないと、送って戻すたびに
 * 位置がずれていく（§5.2）。送り量を共有する形にして、揃うことを型で保証する。
 *
 * 始点は送りと戻しで別に持つ。X のように、下のタブバーと、戻すと上に現れるボタンの
 * 両方を避けるには、送りは下寄り、戻しは上寄りから始める必要があるため。
 *
 * 値を変えるときは with〜 を通す。どれも「終点が画面の外に出ない」「送り量が下限を下回らない」
 * を保ったまま丸める。
 */
data class DragLane(
    /** 縦の線の横位置（送り・戻しで共通）。 */
    val x: Float,
    /** 送り量（画面高に対する比率。送り・戻しで共通）。 */
    val amount: Float,
    /** 送りの始点 y。ここから上へ [amount] だけ動かす。 */
    val forwardFrom: Float,
    /** 戻しの始点 y。ここから下へ [amount] だけ動かす。 */
    val backwardFrom: Float,
) {
    val forwardTo: Float get() = forwardFrom - amount
    val backwardTo: Float get() = backwardFrom + amount

    /** 保存してよい値か。読み込んだ値がこれを満たさなければ、未調整として扱う。 */
    val isValid: Boolean
        get() = x in X_MIN..X_MAX &&
            amount in AMOUNT_MIN..1f &&
            forwardFrom in 0f..1f && backwardFrom in 0f..1f &&
            forwardTo >= 0f && backwardTo <= 1f

    fun withX(value: Float): DragLane = copy(x = value.coerceIn(X_MIN, X_MAX))

    /**
     * 送りの始点を動かす。送り量は変えない。
     * 終点が画面の上に出るときは、始点のほうを止める（送り量を勝手に変えない）。
     *
     * @param startRange 指を置いてよい範囲（システムジェスチャ領域の外）。
     */
    fun withForwardFrom(y: Float, startRange: ClosedFloatingPointRange<Float>): DragLane {
        val lo = maxOf(startRange.start, amount)
        val hi = maxOf(lo, startRange.endInclusive)
        return copy(forwardFrom = y.coerceIn(lo, hi))
    }

    /** 戻しの始点を動かす。考え方は [withForwardFrom] と同じ。 */
    fun withBackwardFrom(y: Float, startRange: ClosedFloatingPointRange<Float>): DragLane {
        val hi = minOf(startRange.endInclusive, 1f - amount)
        val lo = minOf(hi, startRange.start)
        return copy(backwardFrom = y.coerceIn(lo, hi))
    }

    /**
     * 送り量を変える（送り・戻しの両方に効く）。調整している向きの始点は動かさない。
     *
     * もう一方の線が画面からはみ出すときは、**もう一方の始点を押して**収める。
     * 押さずに止めると、送りを調整しているのに戻しの始点（既定 0.28）のせいで
     * 送り量が 72% で頭打ちになる（2026-10-17 実機で指摘）。押す先は指を置ける範囲まで。
     *
     * @param editingForward 送りの線を調整しているか。
     */
    fun withAmount(
        value: Float,
        startRange: ClosedFloatingPointRange<Float>,
        editingForward: Boolean,
    ): DragLane = if (editingForward) {
        val max = maxOf(AMOUNT_MIN, minOf(forwardFrom, 1f - startRange.start))
        val a = value.coerceIn(AMOUNT_MIN, max)
        copy(amount = a, backwardFrom = minOf(backwardFrom, 1f - a))
    } else {
        val max = maxOf(AMOUNT_MIN, minOf(1f - backwardFrom, startRange.endInclusive))
        val a = value.coerceIn(AMOUNT_MIN, max)
        copy(amount = a, forwardFrom = maxOf(forwardFrom, a))
    }

    companion object {
        /** v0.11.0 の固定値と同じ（§5.2）。 */
        val DEFAULT = DragLane(x = 0.5f, amount = 0.63f, forwardFrom = 0.80f, backwardFrom = 0.28f)

        /** 横位置の範囲。端から始めると戻るジェスチャになる（§5.5）。 */
        const val X_MIN = 0.15f
        const val X_MAX = 0.85f

        /**
         * 送り量の下限。短すぎるとタップや長押しと判定されるアプリがあり、
         * yomikake の上下スワイプ（画面高の 12%）にも届かなくなる。
         * 上限は決めない（画面に収まる範囲ならよい）。
         */
        const val AMOUNT_MIN = 0.20f

        /** システムジェスチャ領域が取れないときに使う、上下の端よけ（v0.11.0 と同じ）。 */
        const val FALLBACK_EDGE = 0.08f
        val FALLBACK_START_RANGE = FALLBACK_EDGE..(1f - FALLBACK_EDGE)
    }
}
