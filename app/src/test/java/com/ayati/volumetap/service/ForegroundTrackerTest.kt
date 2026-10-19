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

    // --- Chrome の PWA（design-pwa-picker.md §2.2） --------------------------------

    private val yomikake = "org.chromium.webapk.aa98b755d5fa3d28e_v2"
    private val qiita = "org.chromium.webapk.a3c4b5510dbc73491_v2"
    private val webApkActivity = "org.chromium.chrome.browser.webapps.SameTaskWebApkActivity"
    private val tabbedActivity = "org.chromium.chrome.browser.ChromeTabbedActivity"

    private fun withWebApks(vararg apks: WebApk = arrayOf(WebApk(yomikake, chrome, "yomikake"), WebApk(qiita, chrome, "Qiita"))) {
        tracker.setLaunchablePackages(setOf(chrome, launcher, yomikake, qiita))
        tracker.setWebApks(apks.toList())
    }

    /** PWA 用の画面の表示（2026-10-19 に実機で記録したイベントの形）。 */
    private fun showPwa(title: String?) = tracker.onEvent(chrome, webApkActivity, title, stateChanged = true)

    @Test
    fun `PWA 用の画面で名前が一致すれば その PWA を前面とする`() {
        withWebApks()
        assertTrue(showPwa("yomikake"))
        assertEquals(yomikake, tracker.current)
        assertEquals(chrome, tracker.host)
    }

    @Test
    fun `名前が一致しなければ Chrome として扱う`() {
        withWebApks()
        assertTrue(showPwa("知らない PWA"))
        assertEquals(chrome, tracker.current)
        assertNull(tracker.host)
    }

    @Test
    fun `同じ名前の PWA が 2 つあれば決められないので Chrome として扱う`() {
        withWebApks(WebApk(yomikake, chrome, "yomikake"), WebApk(qiita, chrome, "yomikake"))
        showPwa("yomikake")
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `タイトルが無ければ Chrome として扱う`() {
        withWebApks()
        showPwa(null)
        assertEquals(chrome, tracker.current)
    }

    /** PWA を表示しているあいだも、Chrome のパッケージで再描画のイベントが絶えず届く。 */
    @Test
    fun `PWA の表示中に届く Chrome の再描画では PWA のまま`() {
        withWebApks()
        showPwa("yomikake")
        assertFalse(tracker.onEvent(chrome, "android.widget.FrameLayout"))
        assertFalse(tracker.onEvent(chrome))
        assertEquals(yomikake, tracker.current)
    }

    @Test
    fun `PWA の中のダイアログでは PWA のまま`() {
        withWebApks()
        showPwa("yomikake")
        assertFalse(tracker.onEvent(chrome, "android.app.AlertDialog", stateChanged = true))
        assertEquals(yomikake, tracker.current)
    }

    @Test
    fun `Chrome のタブが前に出たら Chrome に戻る`() {
        withWebApks()
        showPwa("yomikake")
        assertTrue(tracker.onEvent(chrome, tabbedActivity, stateChanged = true))
        assertEquals(chrome, tracker.current)
        assertNull(tracker.host)
    }

    @Test
    fun `PWA どうしの切り替えを追う`() {
        withWebApks()
        showPwa("yomikake")
        assertTrue(showPwa("Qiita"))
        assertEquals(qiita, tracker.current)
    }

    @Test
    fun `PWA からホームに戻ったことは記録する`() {
        withWebApks()
        showPwa("yomikake")
        assertTrue(tracker.onEvent(launcher, "com.miui.home.launcher.Launcher", stateChanged = true))
        assertEquals(launcher, tracker.current)
        assertNull(tracker.host)
    }

    @Test
    fun `PWA の表示中もクイック設定は無視する`() {
        withWebApks()
        showPwa("yomikake")
        assertFalse(tracker.onEvent(systemUi, "android.widget.FrameLayout", stateChanged = true))
        assertEquals(yomikake, tracker.current)
    }

    @Test
    fun `WebAPK の一覧が無ければ PWA 用の画面も Chrome として扱う`() {
        withLaunchableApps()
        assertTrue(showPwa("yomikake"))
        assertEquals(chrome, tracker.current)
    }

    @Test
    fun `resetで PWA のホストも消える`() {
        withWebApks()
        showPwa("yomikake")
        tracker.reset()
        assertNull(tracker.host)
    }

    /**
     * 2026-10-19 に実機で見つかった不具合の回帰テスト。PWA の起動時は WebAPK 自身の画面のイベントが先に届き、
     * PWA 用の画面のイベントより前に Chrome の再描画が届く。ホストが決まっていないと Chrome に戻り、
     * PWA の上での調整が始めた直後に閉じていた。
     */
    @Test
    fun `WebAPK 自身のイベントが先に届いたら その時点でホストを決める`() {
        withWebApks()
        assertTrue(tracker.onEvent(yomikake, "org.chromium.webapk.shell_apk.h2o.SplashActivity", stateChanged = true))
        assertEquals(chrome, tracker.host)
        // PWA 用の画面のイベントより先に Chrome の再描画が届いても、PWA のまま。
        assertFalse(tracker.onEvent(chrome, "android.widget.FrameLayout"))
        assertFalse(showPwa("yomikake"))
        assertEquals(yomikake, tracker.current)
        assertEquals(chrome, tracker.host)
    }

    @Test
    fun `タイトルの無い PWA 用の画面のイベントでは 表示中の PWA のまま`() {
        withWebApks()
        showPwa("yomikake")
        assertFalse(showPwa(null))
        assertFalse(showPwa(""))
        assertEquals(yomikake, tracker.current)
    }

    /** 一致しないタイトルは、ショートカットとして追加した PWA など別の画面の可能性があるので Chrome に倒す。 */
    @Test
    fun `表示中の PWA と違うタイトルなら Chrome として扱う`() {
        withWebApks()
        showPwa("yomikake")
        assertTrue(showPwa("ショートカットの PWA"))
        assertEquals(chrome, tracker.current)
    }
}
