package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.KeySetting
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.TapPoint
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
    private val drag = CalibrationKind.Drag(DragDirection.FORWARD)

    private fun adjusting(profile: AppProfile = AppProfile.NEW_APP, o: ScreenOrientation = ScreenOrientation.PORTRAIT) {
        session.start(chrome, drag)
        session.onForeground(chrome)
        assertTrue(session.begin(o, profile))
    }

    @Test
    fun `対象アプリの起動を待つあいだは設定画面が前面でも閉じない`() {
        session.start(chrome, drag)
        assertFalse(session.onForeground(own))
        assertEquals(CalibrationSession.Phase.WAITING, session.phase)
        assertFalse(session.seenTarget)
        assertFalse(session.onForeground(chrome))
        assertTrue(session.seenTarget)
    }

    @Test
    fun `対象アプリが前面に来る前は始められない`() {
        session.start(chrome, drag)
        assertFalse(session.begin(ScreenOrientation.PORTRAIT, AppProfile.NEW_APP))
    }

    @Test
    fun `対象アプリから離れたら閉じる`() {
        session.start(chrome, drag)
        session.onForeground(chrome)
        assertTrue(session.onForeground("com.miui.home"))
        assertFalse(session.isActive)

        adjusting()
        assertTrue(session.onForeground(own))
        assertFalse(session.isActive)
    }

    @Test
    fun `調整中に向きが変わったら閉じる 待機中は閉じない`() {
        session.start(chrome, drag)
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
        session.start(chrome, drag)
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
        session.start(chrome, drag)
        session.onForeground(chrome)
        assertNull(session.override())
        session.begin(ScreenOrientation.PORTRAIT, AppProfile.NEW_APP)
        session.updateLane(tuned)
        assertEquals(CalibrationOverride.Lane(chrome, ScreenOrientation.PORTRAIT, tuned), session.override())
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

    @Test
    fun `ドラッグの保存は調整した値を書く`() {
        adjusting()
        session.updateLane(tuned)
        val saved = session.saveChange()!!(AppProfile.NEW_APP)
        assertEquals(tuned, saved.dragPortrait)
        assertNull(saved.dragLandscape)
    }

    // --- タップ位置の登録（§7.3.7.1） ---------------------------------------

    private val portraitPoint = TapPoint(0.9f, 0.5f, ScreenOrientation.PORTRAIT)

    private fun tapProfile(portrait: TapPoint? = null, landscape: TapPoint? = null) = AppProfile(
        volumeDown = KeySetting(KeyAction.TAP, tapPortrait = portrait, tapLandscape = landscape),
        volumeUp = KeySetting(KeyAction.DRAG_BACKWARD),
    )

    private fun registering(profile: AppProfile, o: ScreenOrientation = ScreenOrientation.PORTRAIT) {
        session.start(chrome, CalibrationKind.Tap(VolumeKey.DOWN))
        session.onForeground(chrome)
        assertTrue(session.begin(o, profile))
    }

    @Test
    fun `未登録ならしるしを置くまで保存できず 上書きも無い`() {
        registering(tapProfile())
        assertTrue(session.isTap)
        assertNull(session.point)
        assertFalse(session.canSave)
        assertNull(session.saveChange())
        assertNull(session.override())

        session.updatePoint(0.2f, 0.7f)
        assertTrue(session.canSave)
        assertEquals(
            CalibrationOverride.Tap(chrome, ScreenOrientation.PORTRAIT, VolumeKey.DOWN, 0.2f, 0.7f),
            session.override(),
        )
        val saved = session.saveChange()!!(tapProfile())
        assertEquals(TapPoint(0.2f, 0.7f, ScreenOrientation.PORTRAIT), saved.volumeDown.tapPortrait)
        assertNull(saved.volumeDown.tapLandscape)
        // ドラッグの形や、もう一方のキーには触れない。
        assertNull(saved.dragPortrait)
        assertEquals(KeySetting(KeyAction.DRAG_BACKWARD), saved.volumeUp)
    }

    @Test
    fun `登録済みならその位置から始まる`() {
        registering(tapProfile(portrait = portraitPoint))
        assertEquals(portraitPoint, session.point)
        assertTrue(session.canSave)
    }

    @Test
    fun `横持ちが未登録なら縦の比率から始まり 動かさずに保存すると未登録のまま`() {
        val landscape = ScreenOrientation.LANDSCAPE
        registering(tapProfile(portrait = portraitPoint), landscape)
        assertEquals(TapPoint(0.9f, 0.5f, landscape), session.point)

        val unchanged = session.saveChange()!!(tapProfile(portrait = portraitPoint))
        assertEquals(portraitPoint, unchanged.volumeDown.tapPortrait)
        assertNull(unchanged.volumeDown.tapLandscape)

        session.updatePoint(0.95f, 0.5f)
        val moved = session.saveChange()!!(tapProfile(portrait = portraitPoint))
        assertEquals(TapPoint(0.95f, 0.5f, landscape), moved.volumeDown.tapLandscape)
        assertEquals(portraitPoint, moved.volumeDown.tapPortrait)
    }

    @Test
    fun `しるしは画面の中に収める`() {
        registering(tapProfile())
        session.updatePoint(-0.1f, 1.2f)
        assertEquals(TapPoint(0f, 1f, ScreenOrientation.PORTRAIT), session.point)
    }

    @Test
    fun `タップの登録ではドラッグの値を動かさない`() {
        registering(tapProfile())
        session.updateLane(tuned)
        session.resetToFallback()
        assertNull(session.lane)
    }

    @Test
    fun `待機中はしるしを置けない 閉じるとタップの状態も残らない`() {
        session.start(chrome, CalibrationKind.Tap(VolumeKey.DOWN))
        session.onForeground(chrome)
        session.updatePoint(0.5f, 0.5f)
        assertNull(session.point)

        session.begin(ScreenOrientation.PORTRAIT, tapProfile())
        session.updatePoint(0.5f, 0.5f)
        session.close()
        assertNull(session.point)
        assertNull(session.tapKey)
        assertFalse(session.isTap)

        // 続けてドラッグの調整を始めても、タップの状態を引きずらない。
        adjusting()
        assertFalse(session.isTap)
        assertEquals(DragLane.DEFAULT, session.lane)
    }
}
