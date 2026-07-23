package com.ayati.volumetap.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ayati.volumetap.service.VolumeKey
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

    suspend fun setAction(key: VolumeKey, action: KeyAction) {
        val prefKey = when (key) {
            VolumeKey.UP -> Keys.ACTION_UP
            VolumeKey.DOWN -> Keys.ACTION_DOWN
        }
        store.edit { it[prefKey] = action.name }
    }

    suspend fun setTapPoint(key: VolumeKey, point: TapPoint) {
        val (x, y) = keysFor(key, point.orientation)
        store.edit {
            it[x] = point.ratioX
            it[y] = point.ratioY
        }
    }

    suspend fun setLongPressMs(value: Int) {
        store.edit { it[Keys.LONG_PRESS_MS] = value }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        store.edit { it[Keys.HAPTIC] = enabled }
    }

    suspend fun setEnabledPackages(packages: Set<String>) {
        store.edit { it[Keys.ENABLED_PACKAGES] = packages }
    }

    suspend fun setPaused(paused: Boolean) {
        store.edit { it[Keys.PAUSED] = paused }
    }

    suspend fun clearTapPoint(key: VolumeKey, orientation: ScreenOrientation) {
        val (x, y) = keysFor(key, orientation)
        store.edit {
            it.remove(x)
            it.remove(y)
        }
    }

    private fun keysFor(
        key: VolumeKey,
        orientation: ScreenOrientation,
    ): Pair<Preferences.Key<Float>, Preferences.Key<Float>> = when {
        key == VolumeKey.UP && orientation == ScreenOrientation.PORTRAIT -> Keys.UP_PORTRAIT
        key == VolumeKey.UP -> Keys.UP_LANDSCAPE
        orientation == ScreenOrientation.PORTRAIT -> Keys.DOWN_PORTRAIT
        else -> Keys.DOWN_LANDSCAPE
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        volumeUpAction = actionOf(Keys.ACTION_UP),
        volumeDownAction = actionOf(Keys.ACTION_DOWN),
        tapPointUpPortrait = pointOf(Keys.UP_PORTRAIT, ScreenOrientation.PORTRAIT),
        tapPointUpLandscape = pointOf(Keys.UP_LANDSCAPE, ScreenOrientation.LANDSCAPE),
        tapPointDownPortrait = pointOf(Keys.DOWN_PORTRAIT, ScreenOrientation.PORTRAIT),
        tapPointDownLandscape = pointOf(Keys.DOWN_LANDSCAPE, ScreenOrientation.LANDSCAPE),
        longPressMs = this[Keys.LONG_PRESS_MS] ?: AppSettings.DEFAULT_LONG_PRESS_MS,
        hapticFeedback = this[Keys.HAPTIC] ?: true,
        enabledPackages = this[Keys.ENABLED_PACKAGES] ?: emptySet(),
        paused = this[Keys.PAUSED] ?: false,
    )

    private fun Preferences.actionOf(key: Preferences.Key<String>): KeyAction {
        val raw = this[key] ?: return KeyAction.NORMAL
        // 未知の値（ダウングレード時など）は素通しに倒す。
        return KeyAction.entries.firstOrNull { it.name == raw } ?: KeyAction.NORMAL
    }

    private fun Preferences.pointOf(
        keys: Pair<Preferences.Key<Float>, Preferences.Key<Float>>,
        orientation: ScreenOrientation,
    ): TapPoint? {
        val x = this[keys.first] ?: return null
        val y = this[keys.second] ?: return null
        return TapPoint(x, y, orientation)
    }

    private object Keys {
        val ACTION_UP = stringPreferencesKey("action_up")
        val ACTION_DOWN = stringPreferencesKey("action_down")
        val UP_PORTRAIT = floatPreferencesKey("up_portrait_x") to floatPreferencesKey("up_portrait_y")
        val UP_LANDSCAPE = floatPreferencesKey("up_landscape_x") to floatPreferencesKey("up_landscape_y")
        val DOWN_PORTRAIT = floatPreferencesKey("down_portrait_x") to floatPreferencesKey("down_portrait_y")
        val DOWN_LANDSCAPE = floatPreferencesKey("down_landscape_x") to floatPreferencesKey("down_landscape_y")
        val LONG_PRESS_MS = intPreferencesKey("long_press_ms")
        val HAPTIC = booleanPreferencesKey("haptic")
        val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        val PAUSED = booleanPreferencesKey("paused")
    }
}
