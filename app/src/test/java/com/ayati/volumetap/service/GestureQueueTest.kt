package com.ayati.volumetap.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-pageturn.md §5.3「連打の扱い」。 */
class GestureQueueTest {

    private val queue = GestureQueue()
    private val started = mutableListOf<String>()

    private fun gesture(name: String, accepted: Boolean = true): () -> Boolean = {
        started += name
        accepted
    }

    @Test
    fun `空いていればすぐ発行する`() {
        assertTrue(queue.submit(gesture("a")))
        assertEquals(listOf("a"), started)
        assertTrue(queue.isRunning)
    }

    @Test
    fun `実行中は 1 件だけ待たせ 2 件目以降は捨てる`() {
        queue.submit(gesture("a"))
        assertTrue(queue.submit(gesture("b")))
        assertFalse(queue.submit(gesture("c")))
        assertEquals(listOf("a"), started)

        queue.finish()
        assertEquals(listOf("a", "b"), started)

        queue.finish()
        assertEquals(listOf("a", "b"), started)
        assertFalse(queue.isRunning)
    }

    @Test
    fun `待ちを捨てると完了後に何も発行しない`() {
        queue.submit(gesture("a"))
        queue.submit(gesture("b"))
        queue.dropPending()

        queue.finish()
        assertEquals(listOf("a"), started)
        assertFalse(queue.isRunning)
    }

    @Test
    fun `発行に失敗したら実行中にならない`() {
        assertFalse(queue.submit(gesture("a", accepted = false)))
        assertFalse(queue.isRunning)

        // 詰まらずに次を受け付ける。
        assertTrue(queue.submit(gesture("b")))
        assertEquals(listOf("a", "b"), started)
    }

    @Test
    fun `待っていたものの発行に失敗しても詰まらない`() {
        queue.submit(gesture("a"))
        queue.submit(gesture("b", accepted = false))

        queue.finish()
        assertFalse(queue.isRunning)
        assertTrue(queue.submit(gesture("c")))
        assertEquals(listOf("a", "b", "c"), started)
    }

    @Test
    fun `リセット後は空いている`() {
        queue.submit(gesture("a"))
        queue.submit(gesture("b"))
        queue.reset()

        assertFalse(queue.isRunning)
        queue.finish()
        assertEquals(listOf("a"), started)
    }
}
