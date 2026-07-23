package com.ayati.volumetap.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仮想時間の [Scheduler]。[advance] を呼んだ分だけ時間が進み、期限の来た予約を実行する。 */
private class FakeScheduler : Scheduler {
    private class Task(val token: Any, val dueAt: Long, val action: () -> Unit)

    private val tasks = mutableListOf<Task>()
    var now = 0L
        private set

    val pendingCount: Int get() = tasks.size

    override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
        tasks.removeAll { it.token === token }
        tasks.add(Task(token, now + delayMs, action))
    }

    override fun cancel(token: Any) {
        tasks.removeAll { it.token === token }
    }

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
            tasks.remove(next)
            now = next.dueAt
            next.action()
        }
        now = target
    }
}

private class RecordingHost(
    var intercept: Boolean = true,
    var longPress: Long = 300L,
) : ArbiterHost {
    val shortPresses = mutableListOf<VolumeKey>()
    val volumeSteps = mutableListOf<VolumeKey>()

    override fun shouldIntercept(key: VolumeKey) = intercept
    override fun longPressMs() = longPress
    override fun onShortPress(key: VolumeKey) { shortPresses.add(key) }
    override fun onVolumeStep(key: VolumeKey) { volumeSteps.add(key) }
}

class KeyPressArbiterTest {

    private val scheduler = FakeScheduler()
    private val host = RecordingHost()
    private val arbiter = KeyPressArbiter(host, scheduler)

    @Test
    fun `短押しはタップを発火し音量を動かさない`() {
        assertTrue(arbiter.onKeyDown(VolumeKey.UP, 0))
        scheduler.advance(299)
        assertTrue(arbiter.onKeyUp(VolumeKey.UP))

        assertEquals(listOf(VolumeKey.UP), host.shortPresses)
        assertEquals(emptyList<VolumeKey>(), host.volumeSteps)
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.UP))
    }

    @Test
    fun `長押しは音量を刻みタップを発火しない`() {
        arbiter.onKeyDown(VolumeKey.DOWN, 0)
        scheduler.advance(301)

        assertEquals(KeyPressArbiter.State.LONG_PRESS, arbiter.stateOf(VolumeKey.DOWN))
        assertEquals(listOf(VolumeKey.DOWN), host.volumeSteps)
        assertTrue(host.shortPresses.isEmpty())

        // 以降 VOLUME_REPEAT_MS ごとに刻む。
        scheduler.advance(KeyPressArbiter.VOLUME_REPEAT_MS * 3)
        assertEquals(4, host.volumeSteps.size)

        assertTrue(arbiter.onKeyUp(VolumeKey.DOWN))
        assertTrue(host.shortPresses.isEmpty())
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.DOWN))
    }

    @Test
    fun `境界値ちょうどは長押しとして扱う`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)
        scheduler.advance(300)

        assertEquals(KeyPressArbiter.State.LONG_PRESS, arbiter.stateOf(VolumeKey.UP))
        assertEquals(listOf(VolumeKey.UP), host.volumeSteps)
        assertTrue(host.shortPresses.isEmpty())
    }

    @Test
    fun `境界値の1ミリ秒手前は短押しとして扱う`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)
        scheduler.advance(299)
        arbiter.onKeyUp(VolumeKey.UP)

        assertEquals(listOf(VolumeKey.UP), host.shortPresses)
        assertTrue(host.volumeSteps.isEmpty())
    }

    @Test
    fun `介入しない設定ではキーを消費しない`() {
        host.intercept = false

        assertFalse(arbiter.onKeyDown(VolumeKey.UP, 0))
        assertEquals(KeyPressArbiter.State.PASSTHROUGH, arbiter.stateOf(VolumeKey.UP))
        assertFalse(arbiter.onKeyUp(VolumeKey.UP))

        assertTrue(host.shortPresses.isEmpty())
        assertTrue(host.volumeSteps.isEmpty())
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.UP))
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `素通し中のオートリピートも消費しない`() {
        host.intercept = false
        arbiter.onKeyDown(VolumeKey.UP, 0)

        repeat(5) { assertFalse(arbiter.onKeyDown(VolumeKey.UP, it + 1)) }
        assertFalse(arbiter.onKeyUp(VolumeKey.UP))
    }

    @Test
    fun `押下中のオートリピートは消費して無視する`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)

        // OS のオートリピートで二重に判定してはいけない。
        repeat(5) { assertTrue(arbiter.onKeyDown(VolumeKey.UP, it + 1)) }
        scheduler.advance(299)
        arbiter.onKeyUp(VolumeKey.UP)

        assertEquals(1, host.shortPresses.size)
        assertTrue(host.volumeSteps.isEmpty())
    }

    @Test
    fun `UP取りこぼし時は上限タイマーでIDLEへ戻る`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)
        scheduler.advance(301)
        assertEquals(KeyPressArbiter.State.LONG_PRESS, arbiter.stateOf(VolumeKey.UP))

        scheduler.advance(KeyPressArbiter.LONG_PRESS_TIMEOUT_MS)

        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.UP))
        assertEquals(0, scheduler.pendingCount)

        // 復帰後は次の押下を正常に処理できる。
        val steps = host.volumeSteps.size
        assertTrue(arbiter.onKeyDown(VolumeKey.UP, 0))
        scheduler.advance(299)
        arbiter.onKeyUp(VolumeKey.UP)
        assertEquals(1, host.shortPresses.size)
        assertEquals(steps, host.volumeSteps.size)
    }

    @Test
    fun `DOWNを見ていないUPは消費しない`() {
        assertFalse(arbiter.onKeyUp(VolumeKey.UP))
        assertTrue(host.shortPresses.isEmpty())
    }

    @Test
    fun `プラスとマイナスを同時に押しても状態が混線しない`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)
        scheduler.advance(100)
        arbiter.onKeyDown(VolumeKey.DOWN, 0)

        // UP は短押しで離す。DOWN はまだ押下中。
        scheduler.advance(150)
        arbiter.onKeyUp(VolumeKey.UP)

        assertEquals(listOf(VolumeKey.UP), host.shortPresses)
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.UP))
        assertEquals(KeyPressArbiter.State.PENDING, arbiter.stateOf(VolumeKey.DOWN))

        // DOWN はそのまま長押しへ。UP 側の短押しは影響しない。
        scheduler.advance(200)
        assertEquals(KeyPressArbiter.State.LONG_PRESS, arbiter.stateOf(VolumeKey.DOWN))
        assertEquals(listOf(VolumeKey.DOWN), host.volumeSteps)
        assertEquals(listOf(VolumeKey.UP), host.shortPresses)
    }

    @Test
    fun `resetで予約が残らない`() {
        arbiter.onKeyDown(VolumeKey.UP, 0)
        arbiter.onKeyDown(VolumeKey.DOWN, 0)
        scheduler.advance(301)

        arbiter.reset()

        assertEquals(0, scheduler.pendingCount)
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.UP))
        assertEquals(KeyPressArbiter.State.IDLE, arbiter.stateOf(VolumeKey.DOWN))

        val steps = host.volumeSteps.size
        scheduler.advance(1000)
        assertEquals(steps, host.volumeSteps.size)
    }

    @Test
    fun `長押し設定の変更が次の押下から反映される`() {
        host.longPress = 500L
        arbiter.onKeyDown(VolumeKey.UP, 0)
        scheduler.advance(400)

        assertEquals(KeyPressArbiter.State.PENDING, arbiter.stateOf(VolumeKey.UP))
        arbiter.onKeyUp(VolumeKey.UP)
        assertEquals(listOf(VolumeKey.UP), host.shortPresses)
    }
}
