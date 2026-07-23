package com.ayati.volumetap.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {

    private val tracker = ForegroundTracker()
    private val chrome = "com.android.chrome"
    private val systemUi = "com.android.systemui"
    private val launcher = "com.sec.android.app.launcher"

    @Test
    fun `通常のアプリ遷移を記録する`() {
        assertTrue(tracker.onEvent(chrome))
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `パッケージ名が無いイベントは無視する`() {
        tracker.onEvent(chrome)
        assertFalse(tracker.onEvent(null))
        assertFalse(tracker.onEvent(""))
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `同じパッケージの再通知では更新しない`() {
        assertTrue(tracker.onEvent(chrome))
        assertFalse(tracker.onEvent(chrome))
        assertEquals(chrome, tracker.current)
    }

    /**
     * 2026-07-23 に実機で見つかった不具合の回帰テスト。
     * クイック設定を開いて閉じたあと、アプリ履歴から戻っても機能しなくなっていた。
     */
    @Test
    fun `クイック設定を経由しても前面アプリが維持される`() {
        tracker.onEvent(chrome)

        // クイック設定パネルを開く → systemui として通知される
        assertFalse(tracker.onEvent(systemUi))
        assertEquals(chrome, tracker.current)

        // パネルを閉じても Chrome のイベントは飛んでこない。
        // アプリ履歴（これも systemui）を経て Chrome に戻る。
        assertFalse(tracker.onEvent(systemUi))
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `ホームに戻ったことは記録する`() {
        tracker.onEvent(chrome)
        assertTrue(tracker.onEvent(launcher))
        assertEquals(launcher, tracker.current)
    }

    @Test
    fun `別アプリへの遷移は記録する`() {
        tracker.onEvent(chrome)
        tracker.onEvent(systemUi)
        assertTrue(tracker.onEvent("com.google.android.gm"))
        assertEquals("com.google.android.gm", tracker.current)
    }

    @Test
    fun `resetで未確定に戻る`() {
        tracker.onEvent(chrome)
        tracker.reset()
        assertNull(tracker.current)
    }
}
