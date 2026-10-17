package com.ayati.volumetap.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.DragLane
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.KeySetting
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.SettingsRepository
import com.ayati.volumetap.data.TapPoint
import com.ayati.volumetap.service.CalibrationRequests
import com.ayati.volumetap.service.DragDirection
import com.ayati.volumetap.service.VolumeKey
import kotlinx.coroutines.launch

/**
 * 1 つのアプリの割り当てを編集する画面（design-pageturn.md §7.2）。
 *
 * パッケージ名を渡さなければ「その他のアプリ」（個別の割り当てを持たないアプリ）を編集する。
 */
class ProfileEditActivity : ComponentActivity() {

    /** 調整はサービスが重ね表示を出すので、ユーザー補助が有効なときだけできる。 */
    private val serviceEnabled = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = isVolumeTapServiceEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageName: String? = intent.getStringExtra(EXTRA_PACKAGE)
        val repository = SettingsRepository(this)

        setContent {
            VolumeTapTheme {
                val settings by repository.settings.collectAsState(initial = null)
                val app by produceState<InstalledApp?>(initialValue = null) {
                    if (packageName != null) {
                        value = InstalledApps.of(this@ProfileEditActivity, listOf(packageName))
                            .values.firstOrNull()
                    }
                }
                val current = settings ?: return@VolumeTapTheme
                // 削除した直後など、プロファイルが消えていたら閉じる。
                val removed = packageName != null && packageName !in current.profiles
                LaunchedEffect(removed) { if (removed) finish() }
                if (removed) return@VolumeTapTheme
                val title = if (packageName == null) {
                    stringResource(R.string.default_profile)
                } else {
                    app?.label ?: packageName
                }
                ProfileEditScreen(
                    title = title,
                    isDefault = packageName == null,
                    profile = profileOf(current, packageName),
                    serviceEnabled = serviceEnabled.value,
                    onCalibrate = { packageName?.let { startCalibration(it, profileOf(current, it)) } },
                    onResetDragLane = { orientation ->
                        lifecycleScope.launch {
                            repository.updateProfile(packageName) { it.withDragLane(orientation, null) }
                        }
                    },
                    onBack = ::finish,
                    onActionChange = { key, action ->
                        lifecycleScope.launch {
                            repository.updateProfile(packageName) { p ->
                                p.with(key) { it.copy(action = action) }
                            }
                        }
                    },
                    onRegisterPoint = { key ->
                        startActivity(PickPointActivity.intent(this, packageName, key))
                    },
                    onClearPoint = { key, orientation ->
                        lifecycleScope.launch {
                            repository.updateProfile(packageName) { p ->
                                p.with(key) { it.withoutTapPoint(orientation) }
                            }
                        }
                    },
                    onDelete = {
                        if (packageName != null) {
                            lifecycleScope.launch {
                                repository.removeProfile(packageName)
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    /**
     * 対象アプリを起動し、サービスに調整を依頼する（design-pageturn.md §7.3.4）。
     * 対象アプリが前面に来たら、サービスが待機の帯を出す。
     */
    private fun startCalibration(packageName: String, profile: AppProfile) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, R.string.calibrate_launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 送りに割り当てたキーがあれば送りから。
        val direction = if (profile.volumeDown.action == KeyAction.DRAG_FORWARD ||
            profile.volumeUp.action == KeyAction.DRAG_FORWARD
        ) {
            DragDirection.FORWARD
        } else {
            DragDirection.BACKWARD
        }
        CalibrationRequests.request(packageName, direction)
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun profileOf(settings: AppSettings, packageName: String?): AppProfile =
        if (packageName == null) settings.defaultProfile else settings.profileFor(packageName)

    companion object {
        private const val EXTRA_PACKAGE = "package"

        /** @param packageName null なら「その他のアプリ」。 */
        fun intent(context: Context, packageName: String?): Intent =
            Intent(context, ProfileEditActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(
    title: String,
    isDefault: Boolean,
    profile: AppProfile,
    serviceEnabled: Boolean,
    onCalibrate: () -> Unit,
    onResetDragLane: (ScreenOrientation) -> Unit,
    onBack: () -> Unit,
    onActionChange: (VolumeKey, KeyAction) -> Unit,
    onRegisterPoint: (VolumeKey) -> Unit,
    onClearPoint: (VolumeKey, ScreenOrientation) -> Unit,
    onDelete: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isDefault) {
                // 「その他」でドラッグを選ぶと、ホーム画面や地図でも画面が動く。
                Text(
                    text = stringResource(R.string.default_profile_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.model_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 送り（−）を先に置く。読み進める操作のほうが使う頻度が高い。
            for (key in listOf(VolumeKey.DOWN, VolumeKey.UP)) {
                KeySection(
                    title = stringResource(
                        if (key == VolumeKey.UP) R.string.volume_up_section else R.string.volume_down_section,
                    ),
                    setting = profile.settingFor(key),
                    onSelect = { onActionChange(key, it) },
                    onRegister = { onRegisterPoint(key) },
                    onClear = { onClearPoint(key, it) },
                )
            }
            // 「その他のアプリ」は重ねる先のアプリが決まらないので調整できない（§7.3.1）。
            if (!isDefault && profile.usesDrag) {
                DragLaneSection(
                    appLabel = title,
                    profile = profile,
                    serviceEnabled = serviceEnabled,
                    onCalibrate = onCalibrate,
                    onReset = onResetDragLane,
                )
            }
            if (!isDefault) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.delete_profile))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun KeySection(
    title: String,
    setting: KeySetting,
    onSelect: (KeyAction) -> Unit,
    onRegister: () -> Unit,
    onClear: (ScreenOrientation) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                for (action in KeyAction.entries) {
                    ActionRadio(action, setting.action, onSelect)
                }
            }
            when (setting.action) {
                KeyAction.TAP -> TapPointSettings(setting, onRegister, onClear)
                KeyAction.DRAG_FORWARD, KeyAction.DRAG_BACKWARD -> Text(
                    text = stringResource(
                        if (setting.action == KeyAction.DRAG_FORWARD) {
                            R.string.drag_forward_hint
                        } else {
                            R.string.drag_backward_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                KeyAction.NORMAL -> Unit
            }
        }
    }
}

/**
 * ドラッグの位置と送り量（design-pageturn.md §7.3.8）。送り量を＋と−で共有するので、
 * キーごとではなくアプリに 1 つ。
 */
@Composable
private fun DragLaneSection(
    appLabel: String,
    profile: AppProfile,
    serviceEnabled: Boolean,
    onCalibrate: () -> Unit,
    onReset: (ScreenOrientation) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.drag_lane_section),
                style = MaterialTheme.typography.titleMedium,
            )
            for (orientation in ScreenOrientation.entries) {
                val own = profile.dragLaneOrNull(orientation)
                val summary = when {
                    own != null -> stringResource(R.string.drag_lane_custom, percent(own))
                    orientation == ScreenOrientation.LANDSCAPE && profile.dragPortrait != null ->
                        stringResource(R.string.drag_lane_same_as_portrait)
                    else -> stringResource(R.string.drag_lane_default, percent(DragLane.DEFAULT))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (orientation == ScreenOrientation.PORTRAIT) {
                                R.string.drag_lane_portrait
                            } else {
                                R.string.drag_lane_landscape
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (own != null) {
                        TextButton(onClick = { onReset(orientation) }) {
                            Text(stringResource(R.string.drag_lane_reset))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onCalibrate,
                enabled = serviceEnabled,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.calibrate_on_app, appLabel))
            }
            Text(
                text = stringResource(
                    if (serviceEnabled) R.string.calibrate_hint else R.string.calibrate_needs_service,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (serviceEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun percent(lane: DragLane): Int = Math.round(lane.amount * 100)

@Composable
private fun TapPointSettings(
    setting: KeySetting,
    onRegister: () -> Unit,
    onClear: (ScreenOrientation) -> Unit,
) {
    TapPointRow(R.string.tap_point_portrait, setting.tapPortrait) {
        onClear(ScreenOrientation.PORTRAIT)
    }
    TapPointRow(R.string.tap_point_landscape, setting.tapLandscape) {
        onClear(ScreenOrientation.LANDSCAPE)
    }
    OutlinedButton(
        onClick = onRegister,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(R.string.register))
    }
    Text(
        text = stringResource(R.string.register_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (setting.tapPortrait == null && setting.tapLandscape == null) {
        // 座標が無いあいだは素通しに倒れるため、そのことを明示する。
        Text(
            text = stringResource(R.string.tap_point_unregistered),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun TapPointRow(labelRes: Int, point: TapPoint?, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = point?.let { stringResource(R.string.tap_point_value, it.ratioX, it.ratioY) }
                ?: stringResource(R.string.tap_point_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (point != null) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
        }
    }
}

@Composable
private fun ActionRadio(
    value: KeyAction,
    selected: KeyAction,
    onSelect: (KeyAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected == value,
                onClick = { onSelect(value) },
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = null)
        Text(
            text = stringResource(actionLabel(value)),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** 送り方式の説明（ラジオボタン用）。 */
internal fun actionLabel(action: KeyAction): Int = when (action) {
    KeyAction.NORMAL -> R.string.action_normal
    KeyAction.TAP -> R.string.action_tap
    KeyAction.DRAG_FORWARD -> R.string.action_drag_forward
    KeyAction.DRAG_BACKWARD -> R.string.action_drag_backward
}

/** 送り方式の短い名前（一覧の要約用）。 */
internal fun actionShortLabel(action: KeyAction): Int = when (action) {
    KeyAction.NORMAL -> R.string.action_short_normal
    KeyAction.TAP -> R.string.action_short_tap
    KeyAction.DRAG_FORWARD -> R.string.action_short_forward
    KeyAction.DRAG_BACKWARD -> R.string.action_short_backward
}
