package com.ayati.volumetap.service

import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.AppSettings
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

    private val forward = ResolvedAction.Drag(
        DragGeometry.X, DragGeometry.FORWARD_FROM, DragGeometry.X, DragGeometry.FORWARD_TO,
    )
    private val backward = ResolvedAction.Drag(
        DragGeometry.X, DragGeometry.BACKWARD_FROM, DragGeometry.X, DragGeometry.BACKWARD_TO,
    )

    private val chromeOnly = AppSettings(profiles = mapOf(chrome to AppProfile.NEW_APP))

    private fun resolve(
        settings: AppSettings,
        foreground: String?,
        key: VolumeKey = VolumeKey.DOWN,
        orientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    ) = ProfileResolver.resolve(settings, foreground, own, key, orientation)

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
    fun `送りと戻しの移動量は揃っている`() {
        val forwardDistance = DragGeometry.FORWARD_FROM - DragGeometry.FORWARD_TO
        val backwardDistance = DragGeometry.BACKWARD_TO - DragGeometry.BACKWARD_FROM
        assertEquals(forwardDistance, backwardDistance, 1e-6f)
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
