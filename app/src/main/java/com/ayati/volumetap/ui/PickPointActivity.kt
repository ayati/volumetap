package com.ayati.volumetap.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.SettingsRepository
import com.ayati.volumetap.data.TapPoint
import com.ayati.volumetap.service.ScreenMetrics
import com.ayati.volumetap.service.VolumeKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 座標登録画面（設計書 §6.2）。
 *
 * 白背景の全画面を出し、タップされた位置を画面サイズに対する比率で保存する。
 * 現在の画面の向きに対して登録するため、横持ちの座標を登録したいときは
 * 端末を横にしてからこの画面を開く。
 */
class PickPointActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ノッチ部分も含めて全画面を使う。ここが欠けると比率がずれる。
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val key = runCatching {
            VolumeKey.valueOf(intent.getStringExtra(EXTRA_KEY).orEmpty())
        }.getOrDefault(VolumeKey.UP)
        val repository = SettingsRepository(this)

        setContent {
            var existing by remember { mutableStateOf<TapPoint?>(null) }
            LaunchedEffect(Unit) {
                existing = repository.settings.first().tapPointFor(key, currentOrientation())
            }
            PickPointScreen(
                existing = existing,
                onConfirm = { ratioX, ratioY ->
                    lifecycleScope.launch {
                        repository.setTapPoint(
                            key,
                            TapPoint(ratioX, ratioY, currentOrientation()),
                        )
                        finish()
                    }
                },
                onCancel = ::finish,
            )
        }
    }

    private fun currentOrientation(): ScreenOrientation =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ScreenOrientation.LANDSCAPE
        } else {
            ScreenOrientation.PORTRAIT
        }

    companion object {
        private const val EXTRA_KEY = "volume_key"

        fun intent(context: Context, key: VolumeKey): Intent =
            Intent(context, PickPointActivity::class.java).putExtra(EXTRA_KEY, key.name)
    }
}

/** タップ位置を示すマーカーの半径。 */
private val MarkerRadius = 22.dp

/**
 * 白背景の全画面ピッカー。
 *
 * ⚠️ **捕捉レイヤーを操作ボタンの親にしてはいけない。** 親に置くと Compose の
 * 入力伝播でタップが常に親側に取られ、ボタンが一切押せなくなる。
 * 捕捉レイヤーとボタン群は兄弟に並べ、ボタンを上に重ねる。
 * Compose はヒットテストを最前面の子から行い、最初に当たった時点で止まるため、
 * ボタン上のタップはボタンだけが、それ以外のタップは捕捉レイヤーだけが受け取る。
 * ボタン以外の余白は捕捉レイヤーに素通しするので、画面下端の座標も登録できる。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PickPointScreen(
    existing: TapPoint?,
    onConfirm: (Float, Float) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val screen = remember { ScreenMetrics.realSize(context) }

    // ratio は raw 座標（画面全体の座標系）から求める。描画にはローカル座標を使う。
    var pickedRatio by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var pickedLocal by remember { mutableStateOf<Offset?>(null) }

    val markerPx = with(LocalDensity.current) { MarkerRadius.toPx() }

    // 選んだ位置の上に操作バーが重ならないよう、反対側へ寄せる。
    // これがないと画面下端付近の座標を選んだときにマーカーがバーで隠れる。
    val barAlignment = if ((pickedLocal?.y ?: 0f) > screen.y * 0.55f) {
        Alignment.TopCenter
    } else {
        Alignment.BottomCenter
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // --- 捕捉レイヤー（最背面） -------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .pointerInteropFilter { event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        // rawX/rawY は画面全体の座標系。dispatchGesture と同じ基準になる。
                        pickedRatio = (event.rawX / screen.x) to (event.rawY / screen.y)
                        pickedLocal = Offset(event.x, event.y)
                    }
                    true
                }
                .drawBehind {
                    // 既存の登録位置を薄く表示する。
                    if (pickedLocal == null && existing != null) {
                        drawCircle(
                            color = Color(0x331E88D8),
                            radius = markerPx,
                            center = Offset(
                                existing.ratioX * size.width,
                                existing.ratioY * size.height,
                            ),
                        )
                    }
                    pickedLocal?.let {
                        drawCircle(color = Color(0xFF1E88D8), radius = markerPx, center = it)
                        drawCircle(color = Color.White, radius = markerPx * 0.35f, center = it)
                    }
                },
        )

        // --- ガイド文（ポインタ入力を持たないので捕捉レイヤーに素通しする） ---
        Text(
            text = stringResource(
                if (pickedRatio == null) R.string.pick_prompt else R.string.pick_confirm_prompt,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp),
        )

        // --- 操作バー（最前面。ボタンだけがタップを受け取る） -----------
        Column(
            modifier = Modifier
                .align(barAlignment)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pickedRatio?.let { (x, y) ->
                Text(
                    text = stringResource(R.string.pick_coordinates, x, y),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9E9E9E),
                )
            }
            // ボタンを 1 行に並べると、幅の狭い端末（Galaxy S23 など）で
            // 末尾のボタンが潰れて線になる。主操作は独立した行に出し、
            // 副操作だけを横並びにする。
            if (pickedRatio != null) {
                Button(
                    onClick = { pickedRatio?.let { onConfirm(it.first, it.second) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 320.dp),
                ) {
                    Text(stringResource(R.string.pick_decide))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                if (pickedRatio != null) {
                    OutlinedButton(
                        onClick = {
                            pickedRatio = null
                            pickedLocal = null
                        },
                    ) {
                        Text(stringResource(R.string.pick_retry))
                    }
                }
            }
        }
    }
}
