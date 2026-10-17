package com.ayati.volumetap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** [AppSettings] の永続化。DataStore (Preferences) を使う。 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data
        .catch { e ->
            // 読み込み失敗時は既定値に倒す。設定が壊れて音量が効かなくなるより安全。
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { it.toAppSettings() }

    /**
     * プロファイルを書き換える。[packageName] が null なら既定のプロファイル。
     * 対象のプロファイルがまだ無ければ [AppProfile.NEW_APP] から始める。
     */
    suspend fun updateProfile(packageName: String?, transform: (AppProfile) -> AppProfile) {
        store.edit { prefs ->
            val current = prefs.profiles()
            if (packageName == null) {
                prefs.writeProfiles(transform(current.defaultProfile), current.profiles)
            } else {
                val next = current.profiles.toMutableMap()
                next[packageName] = transform(next[packageName] ?: AppProfile.NEW_APP)
                prefs.writeProfiles(current.defaultProfile, next)
            }
        }
    }

    suspend fun removeProfile(packageName: String) {
        store.edit { prefs ->
            val current = prefs.profiles()
            prefs.writeProfiles(current.defaultProfile, current.profiles - packageName)
        }
    }

    suspend fun setLongPressMs(value: Int) {
        store.edit { it[Keys.LONG_PRESS_MS] = value }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        store.edit { it[Keys.HAPTIC] = enabled }
    }

    suspend fun setPaused(paused: Boolean) {
        store.edit { it[Keys.PAUSED] = paused }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val profiles = profiles()
        return AppSettings(
            profiles = profiles.profiles,
            defaultProfile = profiles.defaultProfile,
            longPressMs = this[Keys.LONG_PRESS_MS] ?: AppSettings.DEFAULT_LONG_PRESS_MS,
            hapticFeedback = this[Keys.HAPTIC] ?: true,
            paused = this[Keys.PAUSED] ?: false,
        )
    }

    /**
     * 保存済みのプロファイル。まだ無ければ v0.10 の設定から読み替える。
     *
     * 旧キーは消さない。新しい形式を一度書けば以後はそちらが優先されるので害はなく、
     * v0.10 に戻したときにも設定が残る。
     */
    private fun Preferences.profiles(): ProfileCodec.Decoded {
        this[Keys.PROFILES]?.let { text ->
            ProfileCodec.decode(text)?.let { return it }
        }
        return legacy().toProfiles()
    }

    private fun MutablePreferences.writeProfiles(
        defaultProfile: AppProfile,
        profiles: Map<String, AppProfile>,
    ) {
        this[Keys.PROFILES] = ProfileCodec.encode(defaultProfile, profiles)
    }

    private fun Preferences.legacy() = LegacySettings(
        volumeUpAction = legacyActionOf(Keys.Legacy.ACTION_UP),
        volumeDownAction = legacyActionOf(Keys.Legacy.ACTION_DOWN),
        tapPointUpPortrait = legacyPointOf(Keys.Legacy.UP_PORTRAIT, ScreenOrientation.PORTRAIT),
        tapPointUpLandscape = legacyPointOf(Keys.Legacy.UP_LANDSCAPE, ScreenOrientation.LANDSCAPE),
        tapPointDownPortrait = legacyPointOf(Keys.Legacy.DOWN_PORTRAIT, ScreenOrientation.PORTRAIT),
        tapPointDownLandscape =
            legacyPointOf(Keys.Legacy.DOWN_LANDSCAPE, ScreenOrientation.LANDSCAPE),
        enabledPackages = this[Keys.Legacy.ENABLED_PACKAGES] ?: emptySet(),
    )

    private fun Preferences.legacyActionOf(key: Preferences.Key<String>): KeyAction {
        val raw = this[key] ?: return KeyAction.NORMAL
        // 未知の値は素通しに倒す。
        return KeyAction.entries.firstOrNull { it.name == raw } ?: KeyAction.NORMAL
    }

    private fun Preferences.legacyPointOf(
        keys: Pair<Preferences.Key<Float>, Preferences.Key<Float>>,
        orientation: ScreenOrientation,
    ): TapPoint? {
        val x = this[keys.first] ?: return null
        val y = this[keys.second] ?: return null
        return TapPoint(x, y, orientation)
    }

    private object Keys {
        /** プロファイル一式（[ProfileCodec]）。v0.11.0 から。 */
        val PROFILES = stringPreferencesKey("profiles")
        val LONG_PRESS_MS = intPreferencesKey("long_press_ms")
        val HAPTIC = booleanPreferencesKey("haptic")
        val PAUSED = booleanPreferencesKey("paused")

        /** v0.10 までのキー。読み替えにだけ使い、書き込まない。 */
        object Legacy {
            val ACTION_UP = stringPreferencesKey("action_up")
            val ACTION_DOWN = stringPreferencesKey("action_down")
            val UP_PORTRAIT =
                floatPreferencesKey("up_portrait_x") to floatPreferencesKey("up_portrait_y")
            val UP_LANDSCAPE =
                floatPreferencesKey("up_landscape_x") to floatPreferencesKey("up_landscape_y")
            val DOWN_PORTRAIT =
                floatPreferencesKey("down_portrait_x") to floatPreferencesKey("down_portrait_y")
            val DOWN_LANDSCAPE =
                floatPreferencesKey("down_landscape_x") to floatPreferencesKey("down_landscape_y")
            val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        }
    }
}
