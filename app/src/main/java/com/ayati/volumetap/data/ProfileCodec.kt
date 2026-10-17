package com.ayati.volumetap.data

/**
 * プロファイル一式の文字列表現（design-pageturn.md §6.3）。
 *
 * DataStore Preferences は入れ子の構造を持てないため、1 つの文字列キーにまとめて保存する。
 * JSON ではなく 1 行 1 アプリの単純な形式にしたのは、Android のスタブなしで単体テストでき、
 * テスト用の依存も増やさずに済むため。
 *
 * ```
 * v1
 * *|NORMAL,,,,|NORMAL,,,,                       ← 既定のプロファイル
 * com.android.chrome|DRAG_BACKWARD,,,,|DRAG_FORWARD,,,,
 * jp.example.reader|TAP,0.85,0.5,,|TAP,0.15,0.5,0.1,0.5
 * ```
 *
 * 各キーは `種類,縦x,縦y,横x,横y`（未登録の座標は空）。パッケージ名に `|` や `,` は現れない。
 *
 * **壊れた行・未知の値は読み飛ばす。** 読めないプロファイルは「無い」扱いになり、
 * 既定のプロファイル（初期値は音量のまま）に倒れる。危険側（意図しない注入）には転ばない。
 */
object ProfileCodec {

    private const val HEADER = "v1"
    private const val DEFAULT_KEY = "*"

    fun encode(defaultProfile: AppProfile, profiles: Map<String, AppProfile>): String =
        buildString {
            append(HEADER)
            appendLine()
            appendProfile(DEFAULT_KEY, defaultProfile)
            for ((pkg, profile) in profiles.toSortedMap()) {
                appendProfile(pkg, profile)
            }
        }

    /** @return 解釈できなければ null（形式の版が違う、など）。 */
    fun decode(text: String): Decoded? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != HEADER) return null

        var defaultProfile = AppProfile()
        val profiles = LinkedHashMap<String, AppProfile>()
        for (line in lines.drop(1)) {
            val parts = line.split('|')
            if (parts.size != 3 || parts[0].isEmpty()) continue
            val up = decodeKey(parts[1]) ?: continue
            val down = decodeKey(parts[2]) ?: continue
            val profile = AppProfile(volumeUp = up, volumeDown = down)
            if (parts[0] == DEFAULT_KEY) defaultProfile = profile else profiles[parts[0]] = profile
        }
        return Decoded(defaultProfile, profiles)
    }

    data class Decoded(val defaultProfile: AppProfile, val profiles: Map<String, AppProfile>)

    private fun StringBuilder.appendProfile(key: String, profile: AppProfile) {
        append(key).append('|')
        append(encodeKey(profile.volumeUp)).append('|')
        append(encodeKey(profile.volumeDown))
        appendLine()
    }

    private fun encodeKey(setting: KeySetting): String = listOf(
        setting.action.name,
        setting.tapPortrait?.ratioX.str(),
        setting.tapPortrait?.ratioY.str(),
        setting.tapLandscape?.ratioX.str(),
        setting.tapLandscape?.ratioY.str(),
    ).joinToString(",")

    private fun Float?.str(): String = this?.toString().orEmpty()

    private fun decodeKey(text: String): KeySetting? {
        val fields = text.split(',')
        if (fields.size != 5) return null
        // 未知の種類（新しい版で保存した設定をダウングレードで読んだ場合など）は音量に倒す。
        val action = KeyAction.entries.firstOrNull { it.name == fields[0] } ?: KeyAction.NORMAL
        return KeySetting(
            action = action,
            tapPortrait = point(fields[1], fields[2], ScreenOrientation.PORTRAIT),
            tapLandscape = point(fields[3], fields[4], ScreenOrientation.LANDSCAPE),
        )
    }

    private fun point(x: String, y: String, orientation: ScreenOrientation): TapPoint? {
        val rx = x.toFloatOrNull() ?: return null
        val ry = y.toFloatOrNull() ?: return null
        if (rx !in 0f..1f || ry !in 0f..1f) return null
        return TapPoint(rx, ry, orientation)
    }
}

/**
 * v0.10 までの設定（design.md §5.3）。プロファイル導入前の形式で、
 * 新しい形式がまだ保存されていないときにだけ読み替える（design-pageturn.md §6.3）。
 */
data class LegacySettings(
    val volumeUpAction: KeyAction = KeyAction.NORMAL,
    val volumeDownAction: KeyAction = KeyAction.NORMAL,
    val tapPointUpPortrait: TapPoint? = null,
    val tapPointUpLandscape: TapPoint? = null,
    val tapPointDownPortrait: TapPoint? = null,
    val tapPointDownLandscape: TapPoint? = null,
    val enabledPackages: Set<String> = emptySet(),
) {
    /**
     * 挙動が v0.10 と完全に同じになるように読み替える。
     *
     * - 絞り込みなし（すべてのアプリ）→ 旧設定を既定のプロファイルに入れる
     * - 絞り込みあり → 選ばれていた各アプリに旧設定をコピーし、既定は音量のまま
     */
    fun toProfiles(): ProfileCodec.Decoded {
        val profile = AppProfile(
            volumeUp = KeySetting(volumeUpAction, tapPointUpPortrait, tapPointUpLandscape),
            volumeDown = KeySetting(volumeDownAction, tapPointDownPortrait, tapPointDownLandscape),
        )
        return if (enabledPackages.isEmpty()) {
            ProfileCodec.Decoded(defaultProfile = profile, profiles = emptyMap())
        } else {
            ProfileCodec.Decoded(
                defaultProfile = AppProfile(),
                profiles = enabledPackages.associateWith { profile },
            )
        }
    }
}
