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

    /** エッジパネルのような、ランチャーに出ない透過オーバーレイ。 */
    private val edgePanel = "com.samsung.android.app.appsedge"

    /** ランチャー起動可能なアプリが揃った状態にする。 */
    private fun withLaunchableApps() {
        tracker.setLaunchablePackages(setOf(chrome, launcher, "com.google.android.gm"))
    }

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

    /**
     * 2026-08-18 の対策の回帰テスト。
     * Chrome を読んでいる最中に透過オーバーレイ（エッジパネル等）が前に出ると、
     * 前面アプリが入れ替わって絞り込みから外れ、タップを取りこぼしていた。
     */
    @Test
    fun `ランチャーに出ないパッケージでは前面アプリを入れ替えない`() {
        withLaunchableApps()
        tracker.onEvent(chrome)

        assertFalse(tracker.onEvent(edgePanel))
        assertEquals(chrome, tracker.current)

        // オーバーレイが消えたあと Chrome の再描画イベントが来ても、
        // 既に Chrome のままなので更新は起きない。
        assertFalse(tracker.onEvent(chrome))
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `ランチャーに出るアプリへの遷移は一覧取得後も記録する`() {
        withLaunchableApps()
        tracker.onEvent(chrome)

        assertTrue(tracker.onEvent(launcher))
        assertEquals(launcher, tracker.current)

        assertTrue(tracker.onEvent("com.google.android.gm"))
        assertEquals("com.google.android.gm", tracker.current)
    }

    /**
     * 一覧の取得は非同期。揃う前のイベントまで捨てると、前面アプリが空のまま
     * 何も動かなくなるので、未取得のあいだは絞り込まない。
     */
    @Test
    fun `一覧が未取得のあいだは絞り込まない`() {
        assertTrue(tracker.onEvent(edgePanel))
        assertEquals(edgePanel, tracker.current)
    }

    /** 取得に失敗して空集合が渡された場合も、未取得と同じく絞り込まない。 */
    @Test
    fun `空の一覧を渡されたら絞り込まない`() {
        tracker.setLaunchablePackages(emptySet())

        assertTrue(tracker.onEvent(edgePanel))
        assertEquals(edgePanel, tracker.current)
    }

    /** 一覧取得後もクイック設定は無視され続ける（Galaxy S23 の回帰テスト）。 */
    @Test
    fun `一覧取得後もクイック設定は無視する`() {
        withLaunchableApps()
        tracker.onEvent(chrome)

        assertFalse(tracker.onEvent(systemUi))
        assertEquals(chrome, tracker.current)
    }
}
