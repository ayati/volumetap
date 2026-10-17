package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.KeySetting
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.TapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** design-pageturn.md §6.2「介入する条件」。 */
class ProfileResolverTest {

    private val own = "com.ayati.volumetap"
    private val chrome = "com.android.chrome"
    private val launcher = "com.miui.home"

    private fun forwardOf(l: DragLane) = ResolvedAction.Drag(l.x, l.forwardFrom, l.x, l.forwardTo)
    private fun backwardOf(l: DragLane) = ResolvedAction.Drag(l.x, l.backwardFrom, l.x, l.backwardTo)

    private val forward = forwardOf(DragLane.DEFAULT)
    private val backward = backwardOf(DragLane.DEFAULT)

    /** 調整した値（X で戻しの始点を下げ、横位置を右に寄せた想定）。 */
    private val tuned = DragLane(x = 0.7f, amount = 0.58f, forwardFrom = 0.82f, backwardFrom = 0.32f)
    private val tunedLandscape = DragLane(x = 0.5f, amount = 0.5f, forwardFrom = 0.75f, backwardFrom = 0.25f)

    private val chromeOnly = AppSettings(profiles = mapOf(chrome to AppProfile.NEW_APP))

    private fun resolve(
        settings: AppSettings,
        foreground: String?,
        key: VolumeKey = VolumeKey.DOWN,
        orientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
        calibration: CalibrationOverride? = null,
    ) = ProfileResolver.resolve(settings, foreground, own, key, orientation, calibration)

    @Test
    fun `初期状態ではどこでも介入しない`() {
        assertNull(resolve(AppSettings(), chrome))
        assertNull(resolve(AppSettings(), null))
    }

    @Test
    fun `追加したアプリでは −で送り ＋で戻す`() {
        assertEquals(forward, resolve(chromeOnly, chrome, VolumeKey.DOWN))
        assertEquals(backward, resolve(chromeOnly, chrome, VolumeKey.UP))
    }

    @Test
    fun `追加していないアプリでは既定のプロファイルに従う`() {
        assertNull(resolve(chromeOnly, launcher))

        val withDefault = chromeOnly.copy(
            defaultProfile = AppProfile(volumeDown = KeySetting(KeyAction.DRAG_FORWARD)),
        )
        assertEquals(forward, resolve(withDefault, launcher, VolumeKey.DOWN))
        assertNull(resolve(withDefault, launcher, VolumeKey.UP))
    }

    @Test
    fun `既定値は v0_11_0 の固定値と同じ`() {
        assertEquals(ResolvedAction.Drag(0.5f, 0.80f, 0.5f, 0.17f).fromY, forward.fromY, 1e-6f)
        assertEquals(0.17f, forward.toY, 1e-6f)
        assertEquals(0.28f, backward.fromY, 1e-6f)
        assertEquals(0.91f, backward.toY, 1e-6f)
    }

    @Test
    fun `送りと戻しの移動量は揃っている`() {
        for (lane in listOf(DragLane.DEFAULT, tuned, tunedLandscape)) {
            val f = forwardOf(lane)
            val b = backwardOf(lane)
            assertEquals(f.fromY - f.toY, b.toY - b.fromY, 1e-6f)
        }
    }

    @Test
    fun `調整した値を使う`() {
        val settings = AppSettings(profiles = mapOf(chrome to AppProfile.NEW_APP.copy(dragPortrait = tuned)))
        assertEquals(forwardOf(tuned), resolve(settings, chrome, VolumeKey.DOWN))
        assertEquals(backwardOf(tuned), resolve(settings, chrome, VolumeKey.UP))
    }

    @Test
    fun `横持ちは横の値 無ければ縦の値 どちらも無ければ既定値`() {
        val landscape = ScreenOrientation.LANDSCAPE
        val both = AppProfile.NEW_APP.copy(dragPortrait = tuned, dragLandscape = tunedLandscape)
        val portraitOnly = AppProfile.NEW_APP.copy(dragPortrait = tuned)
        val landscapeOnly = AppProfile.NEW_APP.copy(dragLandscape = tunedLandscape)

        fun at(p: AppProfile, o: ScreenOrientation) =
            resolve(AppSettings(profiles = mapOf(chrome to p)), chrome, VolumeKey.DOWN, o)

        assertEquals(forwardOf(tunedLandscape), at(both, landscape))
        assertEquals(forwardOf(tuned), at(portraitOnly, landscape))
        assertEquals(forward, at(landscapeOnly, ScreenOrientation.PORTRAIT))
        assertEquals(forwardOf(tunedLandscape), at(landscapeOnly, landscape))
    }

    @Test
    fun `調整中の値は対象アプリで同じ向きのときだけ使う`() {
        val settings = AppSettings(
            profiles = mapOf(chrome to AppProfile.NEW_APP),
            defaultProfile = AppProfile.NEW_APP,
        )
        val calibrating = CalibrationOverride(chrome, ScreenOrientation.PORTRAIT, tuned)

        assertEquals(forwardOf(tuned), resolve(settings, chrome, calibration = calibrating))
        assertEquals(backwardOf(tuned), resolve(settings, chrome, VolumeKey.UP, calibration = calibrating))
        // 別のアプリ・別の向きでは保存値（既定値）のまま。
        assertEquals(forward, resolve(settings, launcher, calibration = calibrating))
        assertEquals(
            forward,
            resolve(settings, chrome, orientation = ScreenOrientation.LANDSCAPE, calibration = calibrating),
        )
    }

    @Test
    fun `調整中でも音量のままのキーは素通しする`() {
        val settings = AppSettings(
            profiles = mapOf(chrome to AppProfile(volumeDown = KeySetting(KeyAction.DRAG_FORWARD))),
        )
        val calibrating = CalibrationOverride(chrome, ScreenOrientation.PORTRAIT, tuned)
        assertNull(resolve(settings, chrome, VolumeKey.UP, calibration = calibrating))
    }

    @Test
    fun `一時停止中は介入しない`() {
        assertNull(resolve(chromeOnly.copy(paused = true), chrome))
    }

    @Test
    fun `自アプリが前面のときは介入しない`() {
        val everywhere = AppSettings(defaultProfile = AppProfile.NEW_APP)
        assertNull(resolve(everywhere, own))
    }

    @Test
    fun `前面アプリが不明なら既定のプロファイルに従う`() {
        val everywhere = AppSettings(defaultProfile = AppProfile.NEW_APP)
        assertEquals(forward, resolve(everywhere, null))
    }

    @Test
    fun `タップは座標が未登録なら素通しする`() {
        val tapWithoutPoint = AppSettings(
            profiles = mapOf(chrome to AppProfile(volumeDown = KeySetting(KeyAction.TAP))),
        )
        assertNull(resolve(tapWithoutPoint, chrome))
    }

    @Test
    fun `タップは横持ちの座標が無ければ縦の比率を流用する`() {
        val point = TapPoint(0.15f, 0.5f, ScreenOrientation.PORTRAIT)
        val settings = AppSettings(
            profiles = mapOf(
                chrome to AppProfile(volumeDown = KeySetting(KeyAction.TAP, tapPortrait = point)),
            ),
        )
        assertEquals(
            ResolvedAction.Tap(0.15f, 0.5f),
            resolve(settings, chrome, orientation = ScreenOrientation.LANDSCAPE),
        )
    }
}
