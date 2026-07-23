package com.ayati.volumetap.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ayati.volumetap.R
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * クイック設定タイルからの一時停止トグル（設計書 §5.4）。
 *
 * 読書が終わったあとに音量キーを普通に使いたい、という状況で
 * 設定画面を開かずに切り替えられるようにする。
 */
class VolumeTapTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { SettingsRepository(this) }
    private var observer: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observer = scope.launch {
            repository.settings.collect { render(it.paused) }
        }
    }

    override fun onStopListening() {
        observer?.cancel()
        observer = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val paused = repository.settings.first().paused
            repository.setPaused(!paused)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 一時停止中を「オフ」として見せる。タイルが点いている＝機能している、と読める向き。 */
    private fun render(paused: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (paused) R.string.tile_paused else R.string.tile_running,
            )
        }
        tile.updateTile()
    }
}
