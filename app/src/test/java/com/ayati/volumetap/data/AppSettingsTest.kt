package com.ayati.volumetap.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** design-pwa-picker.md §3.2「選択と追加」。 */
class AppSettingsTest {

    private val chrome = "com.android.chrome"
    private val feed = "com.example.feed"
    private val x = "com.twitter.android"

    private val tuned = AppProfile.NEW_APP.copy(
        volumeUp = KeySetting(KeyAction.TAP, TapPoint(0.9f, 0.5f, ScreenOrientation.PORTRAIT)),
        dragPortrait = DragLane(x = 0.7f, amount = 0.58f, forwardFrom = 0.82f, backwardFrom = 0.32f),
    )

    @Test
    fun `写し元の割り当てをそのまま写す`() {
        val settings = AppSettings(profiles = mapOf(chrome to tuned))
        val next = settings.profilesWithAdded(listOf(feed, x), tuned)
        assertEquals(mapOf(chrome to tuned, feed to tuned, x to tuned), next)
    }

    @Test
    fun `既に割り当てのあるアプリは上書きしない`() {
        val settings = AppSettings(profiles = mapOf(chrome to tuned))
        val next = settings.profilesWithAdded(listOf(chrome, feed), AppProfile.NEW_APP)
        assertEquals(tuned, next[chrome])
        assertEquals(AppProfile.NEW_APP, next[feed])
    }

    @Test
    fun `元に戻すと 消した割り当てをそのまま書き戻す`() {
        val removed = mapOf(chrome to tuned, x to AppProfile.NEW_APP)
        assertEquals(removed, AppSettings().profilesWithRestored(removed))
    }

    @Test
    fun `元に戻す前に追加し直したアプリは 新しい割り当てを残す`() {
        val settings = AppSettings(profiles = mapOf(chrome to AppProfile.NEW_APP))
        val next = settings.profilesWithRestored(mapOf(chrome to tuned, x to tuned))
        assertEquals(AppProfile.NEW_APP, next[chrome])
        assertEquals(tuned, next[x])
    }
}
