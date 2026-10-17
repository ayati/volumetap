package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.ScreenOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-pageturn.md §7.3.6「調整の状態」。 */
class CalibrationSessionTest {

    private val own = "com.ayati.volumetap"
    private val chrome = "com.android.chrome"
    private val session = CalibrationSession(own)
    private val tuned = DragLane(0.7f, 0.58f, 0.82f, 0.32f)

    private fun adjusting(profile: AppProfile = AppProfile.NEW_APP, o: ScreenOrientation = ScreenOrientation.PORTRAIT) {
        session.start(chrome, DragDirection.FORWARD)
        session.onForeground(chrome)
        assertTrue(session.begin(o, profile))
    }

    @Test
    fun `対象アプリの起動を待つあいだは設定画面が前面でも閉じない`() {
        session.start(chrome, DragDirection.FORWARD)
        assertFalse(session.onForeground(own))
        assertEquals(CalibrationSession.Phase.WAITING, session.phase)
        assertFalse(session.seenTarget)
        assertFalse(session.onForeground(chrome))
        assertTrue(session.seenTarget)
    }

    @Test
    fun `対象アプリが前面に来る前は始められない`() {
        session.start(chrome, DragDirection.FORWARD)
        assertFalse(session.begin(ScreenOrientation.PORTRAIT, AppProfile.NEW_APP))
    }

    @Test
    fun `対象アプリから離れたら閉じる`() {
        session.start(chrome, DragDirection.FORWARD)
        session.onForeground(chrome)
        assertTrue(session.onForeground("com.miui.home"))
        assertFalse(session.isActive)

        adjusting()
        assertTrue(session.onForeground(own))
        assertFalse(session.isActive)
    }

    @Test
    fun `調整中に向きが変わったら閉じる 待機中は閉じない`() {
        session.start(chrome, DragDirection.FORWARD)
        session.onForeground(chrome)
        assertFalse(session.onOrientation(ScreenOrientation.LANDSCAPE))

        session.begin(ScreenOrientation.PORTRAIT, AppProfile.NEW_APP)
        assertFalse(session.onOrientation(ScreenOrientation.PORTRAIT))
        assertTrue(session.onOrientation(ScreenOrientation.LANDSCAPE))
        assertFalse(session.isActive)
    }

    @Test
    fun `重ね表示のイベントは無視し 設定画面のイベントは無視しない`() {
        assertFalse(session.isOwnOverlayEvent(own, "android.widget.FrameLayout"))
        session.start(chrome, DragDirection.FORWARD)
        assertTrue(session.isOwnOverlayEvent(own, "android.widget.FrameLayout"))
        assertTrue(session.isOwnOverlayEvent(own, null))
        assertFalse(session.isOwnOverlayEvent(own, "$own.ui.ProfileEditActivity"))
        assertFalse(session.isOwnOverlayEvent(chrome, "android.widget.FrameLayout"))
    }

    @Test
    fun `その向きの現在の値から始める`() {
        adjusting(AppProfile.NEW_APP.copy(dragPortrait = tuned), ScreenOrientation.LANDSCAPE)
        assertEquals(tuned, session.lane)
    }

    @Test
    fun `調整中だけ上書きを返す`() {
        session.start(chrome, DragDirection.FORWARD)
        session.onForeground(chrome)
        assertNull(session.override())
        session.begin(ScreenOrientation.PORTRAIT, AppProfile.NEW_APP)
        session.updateLane(tuned)
        assertEquals(CalibrationOverride(chrome, ScreenOrientation.PORTRAIT, tuned), session.override())
    }

    @Test
    fun `未調整のときの値と同じなら未調整として保存する`() {
        adjusting()
        assertNull(session.laneToSave())
        session.updateLane(tuned)
        assertEquals(tuned, session.laneToSave())
        session.resetToFallback()
        assertNull(session.laneToSave())
    }

    @Test
    fun `横持ちの既定に戻すは縦持ちと同じに戻す`() {
        adjusting(AppProfile.NEW_APP.copy(dragPortrait = tuned), ScreenOrientation.LANDSCAPE)
        session.updateLane(DragLane.DEFAULT)
        assertEquals(DragLane.DEFAULT, session.laneToSave())
        session.resetToFallback()
        assertEquals(tuned, session.lane)
        assertNull(session.laneToSave())
    }

    @Test
    fun `閉じると何も残らない`() {
        adjusting()
        session.close()
        assertFalse(session.isActive)
        assertNull(session.target)
        assertNull(session.lane)
        assertNull(session.override())
    }
}
