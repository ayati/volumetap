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
        val small = DragLane.DEFAULT.withAmount(0.3f, range, editingForward = true)
        assertEquals(0.94f, small.withForwardFrom(0.99f, range).forwardFrom, eps)
        assertEquals(0.07f, small.withBackwardFrom(0.01f, range).backwardFrom, eps)
    }

    @Test
    fun `送り量は下限を下回らない`() {
        assertEquals(DragLane.AMOUNT_MIN, DragLane.DEFAULT.withAmount(0.05f, range, true).amount, eps)
        assertEquals(DragLane.AMOUNT_MIN, DragLane.DEFAULT.withAmount(0.05f, range, false).amount, eps)
    }

    @Test
    fun `送りを伸ばすと戻しの始点を押し上げて 72 パーセントで頭打ちにならない`() {
        val lane = DragLane.DEFAULT.withAmount(0.78f, range, editingForward = true)
        assertEquals(0.78f, lane.amount, eps)
        assertEquals(0.80f, lane.forwardFrom, eps)
        assertEquals(0.22f, lane.backwardFrom, eps)
        assertEquals(1f, lane.backwardTo, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `送りの上限は送りの始点から上端まで`() {
        val lane = DragLane.DEFAULT.withForwardFrom(0.94f, range).withAmount(1f, range, editingForward = true)
        assertEquals(0.93f, lane.amount, eps)
        assertEquals(0.01f, lane.forwardTo, eps)
        // 戻しの始点は指を置ける範囲の上端まで押される。
        assertEquals(0.07f, lane.backwardFrom, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `戻しを伸ばすと送りの始点を押し下げる`() {
        val lane = DragLane.DEFAULT.withBackwardFrom(0.1f, range).withAmount(0.85f, range, editingForward = false)
        assertEquals(0.85f, lane.amount, eps)
        assertEquals(0.1f, lane.backwardFrom, eps)
        assertEquals(0.85f, lane.forwardFrom, eps)
        assertEquals(0f, lane.forwardTo, eps)
        assertTrue(lane.isValid)
    }

    @Test
    fun `押さなくて済むときはもう一方の始点を動かさない`() {
        val lane = DragLane.DEFAULT.withAmount(0.5f, range, editingForward = true)
        assertEquals(DragLane.DEFAULT.backwardFrom, lane.backwardFrom, eps)
        assertEquals(DragLane.DEFAULT.forwardFrom, DragLane.DEFAULT.withAmount(0.5f, range, false).forwardFrom, eps)
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
