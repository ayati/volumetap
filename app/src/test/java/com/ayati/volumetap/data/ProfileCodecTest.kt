package com.ayati.volumetap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCodecTest {

    private val chrome = "com.android.chrome"
    private val reader = "jp.example.reader"

    private val tapProfile = AppProfile(
        volumeUp = KeySetting(
            action = KeyAction.TAP,
            tapPortrait = TapPoint(0.85f, 0.5f, ScreenOrientation.PORTRAIT),
        ),
        volumeDown = KeySetting(
            action = KeyAction.TAP,
            tapPortrait = TapPoint(0.15f, 0.5f, ScreenOrientation.PORTRAIT),
            tapLandscape = TapPoint(0.1f, 0.45f, ScreenOrientation.LANDSCAPE),
        ),
    )

    @Test
    fun `書いて読むと元に戻る`() {
        val default = AppProfile(volumeDown = KeySetting(KeyAction.DRAG_FORWARD))
        val profiles = mapOf(chrome to AppProfile.NEW_APP, reader to tapProfile)

        val decoded = ProfileCodec.decode(ProfileCodec.encode(default, profiles))!!

        assertEquals(default, decoded.defaultProfile)
        assertEquals(profiles, decoded.profiles)
    }

    @Test
    fun `ドラッグに切り替えてもタップ座標は保持される`() {
        val switched = tapProfile.with(com.ayati.volumetap.service.VolumeKey.UP) {
            it.copy(action = KeyAction.DRAG_BACKWARD)
        }
        val decoded = ProfileCodec.decode(ProfileCodec.encode(AppProfile(), mapOf(reader to switched)))!!

        val up = decoded.profiles.getValue(reader).volumeUp
        assertEquals(KeyAction.DRAG_BACKWARD, up.action)
        assertEquals(tapProfile.volumeUp.tapPortrait, up.tapPortrait)
    }

    @Test
    fun `版の違う形式は読まない`() {
        assertNull(ProfileCodec.decode("v2\n*|NORMAL,,,,|NORMAL,,,,"))
        assertNull(ProfileCodec.decode(""))
    }

    @Test
    fun `壊れた行は読み飛ばし、ほかの行は生かす`() {
        val text = """
            v1
            *|NORMAL,,,,|NORMAL,,,,
            broken line
            com.example|NORMAL,,,|NORMAL,,,,
            $chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,
        """.trimIndent()

        val decoded = ProfileCodec.decode(text)!!

        assertEquals(setOf(chrome), decoded.profiles.keys)
        assertEquals(AppProfile.NEW_APP, decoded.profiles[chrome])
    }

    /** 新しい版で保存した設定を古い版で読んだときなど。介入しない側に倒す。 */
    @Test
    fun `未知の送り方式は音量のままにする`() {
        val decoded = ProfileCodec.decode("v1\n$chrome|SOMETHING_NEW,,,,|DRAG_FORWARD,,,,")!!

        val profile = decoded.profiles.getValue(chrome)
        assertEquals(KeyAction.NORMAL, profile.volumeUp.action)
        assertEquals(KeyAction.DRAG_FORWARD, profile.volumeDown.action)
    }

    @Test
    fun `範囲外の座標は未登録として扱う`() {
        val decoded = ProfileCodec.decode("v1\n$reader|TAP,1.5,0.5,,|TAP,abc,0.5,,")!!

        val profile = decoded.profiles.getValue(reader)
        assertNull(profile.volumeUp.tapPortrait)
        assertNull(profile.volumeDown.tapPortrait)
    }

    // --- v0.10 からの移行（design-pageturn.md §6.3） ---------------------------

    private val legacyTap = LegacySettings(
        volumeUpAction = KeyAction.TAP,
        volumeDownAction = KeyAction.TAP,
        tapPointUpPortrait = TapPoint(0.85f, 0.5f, ScreenOrientation.PORTRAIT),
        tapPointDownPortrait = TapPoint(0.15f, 0.5f, ScreenOrientation.PORTRAIT),
        tapPointDownLandscape = TapPoint(0.1f, 0.45f, ScreenOrientation.LANDSCAPE),
    )

    @Test
    fun `移行 - 何も設定していなければすべて音量のまま`() {
        val migrated = LegacySettings().toProfiles()

        assertTrue(migrated.profiles.isEmpty())
        assertTrue(migrated.defaultProfile.isPassThrough)
    }

    @Test
    fun `移行 - 絞り込みなしなら旧設定を既定のプロファイルに入れる`() {
        val migrated = legacyTap.toProfiles()

        assertTrue(migrated.profiles.isEmpty())
        assertEquals(tapProfile, migrated.defaultProfile)
    }

    @Test
    fun `移行 - 絞り込みありなら選ばれていたアプリにだけ旧設定を入れる`() {
        val migrated = legacyTap.copy(enabledPackages = setOf(chrome, reader)).toProfiles()

        assertEquals(mapOf(chrome to tapProfile, reader to tapProfile), migrated.profiles)
        assertTrue(migrated.defaultProfile.isPassThrough)
    }

    // --- ドラッグの形（design-pageturn.md §7.3.3） ---

    private val tuned = DragLane(x = 0.7f, amount = 0.58f, forwardFrom = 0.82f, backwardFrom = 0.32f)

    @Test
    fun `ドラッグの形も書いて読むと元に戻る`() {
        val profiles = mapOf(
            chrome to AppProfile.NEW_APP.copy(dragPortrait = tuned),
            reader to AppProfile.NEW_APP.copy(dragLandscape = tuned),
            "x" to AppProfile.NEW_APP.copy(dragPortrait = tuned, dragLandscape = DragLane.DEFAULT),
        )
        val decoded = ProfileCodec.decode(ProfileCodec.encode(AppProfile(), profiles))!!
        assertEquals(profiles, decoded.profiles)
    }

    @Test
    fun `未調整なら v0_11_0 と同じ 3 欄で書く`() {
        val text = ProfileCodec.encode(AppProfile(), mapOf(chrome to AppProfile.NEW_APP))
        val line = text.lines().first { it.startsWith(chrome) }
        assertEquals(3, line.split('|').size)
    }

    @Test
    fun `調整したアプリは 5 欄で書く`() {
        val text = ProfileCodec.encode(AppProfile(), mapOf(chrome to AppProfile.NEW_APP.copy(dragPortrait = tuned)))
        val line = text.lines().first { it.startsWith(chrome) }
        assertEquals("com.android.chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,|0.7,0.58,0.82,0.32|", line)
    }

    @Test
    fun `v0_11_0 の形式（3 欄）をそのまま読める`() {
        val decoded = ProfileCodec.decode("v1\n*|NORMAL,,,,|NORMAL,,,,\n$chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,\n")!!
        assertEquals(AppProfile.NEW_APP, decoded.profiles[chrome])
    }

    @Test
    fun `壊れたドラッグの欄はその向きだけ未調整にしてプロファイルは残す`() {
        val decoded = ProfileCodec.decode(
            "v1\n$chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,|0.7,0.58,abc,0.32|0.5,0.5,0.75,0.25\n" +
                // 終点が画面の外に出る値・送り量が下限未満の値も読み捨てる。
                "$reader|NORMAL,,,,|DRAG_FORWARD,,,,|0.5,0.9,0.8,0.2|0.5,0.1,0.8,0.2\n",
        )!!
        assertEquals(
            AppProfile.NEW_APP.copy(dragLandscape = DragLane(0.5f, 0.5f, 0.75f, 0.25f)),
            decoded.profiles[chrome],
        )
        assertEquals(AppProfile(volumeDown = KeySetting(KeyAction.DRAG_FORWARD)), decoded.profiles[reader])
    }

    @Test
    fun `欄の数が 3 でも 5 でもない行は読み飛ばす`() {
        val decoded = ProfileCodec.decode("v1\n$chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,|0.7,0.58,0.82,0.32\n")!!
        assertNull(decoded.profiles[chrome])
    }
}
