package com.ayati.volumetap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-pageturn.md §7.3.2「調整できる値」。 */
class DragLaneTest {

    /** 試作機の実測（上 162px・下 135px、画面高 2392px）に近い範囲。 */
    private val range = 0.07f..0.94f
    private val eps = 1e-5f

    @Test
    fun `既定値は保存できる値`() {
        assertTrue(DragLane.DEFAULT.isValid)
        assertEquals(0.17f, DragLane.DEFAULT.forwardTo, eps)
        assertEquals(0.91f, DragLane.DEFAULT.backwardTo, eps)
    }

    @Test
    fun `始点を動かしても送り量は変わらない`() {
        val moved = DragLane.DEFAULT.withForwardFrom(0.7f, range).withBackwardFrom(0.3f, range)
        assertEquals(DragLane.DEFAULT.amount, moved.amount, eps)
        assertEquals(0.7f, moved.forwardFrom, eps)
        assertEquals(0.3f, moved.backwardFrom, eps)
    }

    @Test
    fun `送りの終点が画面の上に出るなら始点のほうを止める`() {
        val lane = DragLane.DEFAULT.withForwardFrom(0.4f, range)
        assertEquals(DragLane.DEFAULT.amount, lane.forwardFrom, eps)
        assertEquals(0f, lane.forwardTo, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `戻しの終点が画面の下に出るなら始点のほうを止める`() {
        val lane = DragLane.DEFAULT.withBackwardFrom(0.6f, range)
        assertEquals(1f - DragLane.DEFAULT.amount, lane.backwardFrom, eps)
        assertEquals(1f, lane.backwardTo, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `始点はシステムジェスチャ領域に入らない`() {
        val small = DragLane.DEFAULT.withAmount(0.3f)
        assertEquals(0.94f, small.withForwardFrom(0.99f, range).forwardFrom, eps)
        assertEquals(0.07f, small.withBackwardFrom(0.01f, range).backwardFrom, eps)
    }

    @Test
    fun `送り量は下限を下回らない`() {
        assertEquals(DragLane.AMOUNT_MIN, DragLane.DEFAULT.withAmount(0.05f).amount, eps)
    }

    @Test
    fun `送り量はどちらの終点も画面に収まる範囲まで増やせる`() {
        // 送りの始点 0.94 から上端まで、戻しの始点 0.06 から下端まで。
        val lane = DragLane(0.5f, 0.5f, 0.94f, 0.07f).withAmount(1f)
        assertEquals(0.93f, lane.amount, eps)
        assertEquals(0.01f, lane.forwardTo, eps)
        assertEquals(1f, lane.backwardTo, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `送り量の上限は近いほうの終点で決まる`() {
        val lane = DragLane.DEFAULT.withAmount(1f)
        // 送りの始点 0.80 → 上端まで 0.80、戻しの始点 0.28 → 下端まで 0.72。
        assertEquals(0.72f, lane.amount, eps)
    }

    @Test
    fun `横位置は端よけの範囲に丸める`() {
        assertEquals(DragLane.X_MIN, DragLane.DEFAULT.withX(0f).x, eps)
        assertEquals(DragLane.X_MAX, DragLane.DEFAULT.withX(1f).x, eps)
        assertEquals(0.3f, DragLane.DEFAULT.withX(0.3f).x, eps)
    }

    @Test
    fun `範囲外の値は保存できない`() {
        assertFalse(DragLane(0.5f, 0.9f, 0.8f, 0.2f).isValid)
        assertFalse(DragLane(0.5f, 0.1f, 0.8f, 0.2f).isValid)
        assertFalse(DragLane(0.05f, 0.5f, 0.8f, 0.2f).isValid)
    }

    @Test
    fun `プロファイルの向きごとの値`() {
        val tuned = DragLane(0.7f, 0.58f, 0.82f, 0.32f)
        val p = AppProfile(dragPortrait = tuned)
        assertEquals(tuned, p.dragLaneFor(ScreenOrientation.LANDSCAPE))
        assertEquals(tuned, p.fallbackDragLane(ScreenOrientation.LANDSCAPE))
        assertEquals(DragLane.DEFAULT, p.fallbackDragLane(ScreenOrientation.PORTRAIT))
        assertEquals(null, p.dragLaneOrNull(ScreenOrientation.LANDSCAPE))
        assertEquals(DragLane.DEFAULT, AppProfile().dragLaneFor(ScreenOrientation.LANDSCAPE))
    }
}
