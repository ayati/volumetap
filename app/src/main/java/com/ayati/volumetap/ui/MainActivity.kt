package com.ayati.volumetap.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.KeyAction
import com.ayati.volumetap.data.ScreenOrientation
import com.ayati.volumetap.data.SettingsRepository
import com.ayati.volumetap.data.TapPoint
import com.ayati.volumetap.service.VolumeKey
import com.ayati.volumetap.service.VolumeTapService
import kotlinx.coroutines.launch

/**
 * 設定画面（設計書 §6.1）。
 *
 * Phase 1 の時点では座標登録画面がまだ無いため、モード選択・長押し判定・
 * 動作確認用スイッチのみ。Phase 3 で最終形に仕上げる。
 */
class MainActivity : ComponentActivity() {

    private val serviceEnabled = mutableStateOf(false)
    private val powerRestricted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SettingsRepository(this)
        setContent {
            VolumeTapTheme {
                val settings by repository.settings.collectAsState(initial = AppSettings())
                SettingsScreen(
                    settings = settings,
                    serviceEnabled = serviceEnabled.value,
                    powerRestricted = powerRestricted.value,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenAppInfo = ::openAppInfo,
                    onActionChange = { key, action ->
                        lifecycleScope.launch { repository.setAction(key, action) }
                    },
                    onLongPressChange = {
                        lifecycleScope.launch { repository.setLongPressMs(it) }
                    },
                    onHapticChange = {
                        lifecycleScope.launch { repository.setHapticFeedback(it) }
                    },
                    onRegisterPoint = { key ->
                        startActivity(PickPointActivity.intent(this, key))
                    },
                    onClearPoint = { key, orientation ->
                        lifecycleScope.launch { repository.clearTapPoint(key, orientation) }
                    },
                    onPausedChange = {
                        lifecycleScope.launch { repository.setPaused(it) }
                    },
                    onOpenAppPicker = {
                        startActivity(Intent(this, AppPickerActivity::class.java))
                    },
                    onOpenAbout = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled.value = isServiceEnabled()
        powerRestricted.value = isPowerRestricted()
    }

    /**
     * HyperOS / MIUI の省電力機能にサービスを凍結されうる状態か（設計書 §8）。
     *
     * Xiaomi 端末は裏に回ったプロセスを数秒で凍結し、そのあいだ音量キーが届かない。
     * アプリ情報の「バッテリーセーバー：制限なし」で防げる。この設定は Android 標準の
     * 省電力除外にも反映されるため、[PowerManager.isIgnoringBatteryOptimizations] で判定できる。
     * 「自動起動」は公開 API で読めないので判定に含めない。
     */
    private fun isPowerRestricted(): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return false
        val power = getSystemService(PowerManager::class.java) ?: return false
        return !power.isIgnoringBatteryOptimizations(packageName)
    }

    /** アプリ情報画面。HyperOS ではここにバッテリーセーバーと自動起動がある。 */
    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_app_info_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** ユーザー補助サービスが有効かどうか。設定アプリから戻るたびに評価し直す。 */
    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val target = "$packageName/${VolumeTapService::class.java.name}"
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id == target || it.id.startsWith("$packageName/") }
    }

    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    serviceEnabled: Boolean,
    powerRestricted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onActionChange: (VolumeKey, KeyAction) -> Unit,
    onLongPressChange: (Int) -> Unit,
    onHapticChange: (Boolean) -> Unit,
    onRegisterPoint: (VolumeKey) -> Unit,
    onClearPoint: (VolumeKey, ScreenOrientation) -> Unit,
    onPausedChange: (Boolean) -> Unit,
    onOpenAppPicker: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 省電力設定を変えると端末がアプリを強制停止し、ユーザー補助がオフになる。
            // 「省電力設定 → ユーザー補助」の順に読めるよう、状態カードより上に置く。
            if (powerRestricted) PowerRestrictionCard(onOpenAppInfo)
            ServiceStatusCard(serviceEnabled, onOpenAccessibilitySettings)

            SectionHeader(R.string.section_assignment)
            Text(
                text = stringResource(R.string.model_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionSection(
                title = stringResource(R.string.volume_up_section),
                selected = settings.volumeUpAction,
                portrait = settings.tapPointUpPortrait,
                landscape = settings.tapPointUpLandscape,
                onSelect = { onActionChange(VolumeKey.UP, it) },
                onRegister = { onRegisterPoint(VolumeKey.UP) },
                onClear = { onClearPoint(VolumeKey.UP, it) },
            )
            ActionSection(
                title = stringResource(R.string.volume_down_section),
                selected = settings.volumeDownAction,
                portrait = settings.tapPointDownPortrait,
                landscape = settings.tapPointDownLandscape,
                onSelect = { onActionChange(VolumeKey.DOWN, it) },
                onRegister = { onRegisterPoint(VolumeKey.DOWN) },
                onClear = { onClearPoint(VolumeKey.DOWN, it) },
            )

            SectionHeader(R.string.section_scope)
            AppFilterRow(settings.enabledPackages.size, onOpenAppPicker)
            PauseSection(settings.paused, onPausedChange)

            SectionHeader(R.string.section_advanced)
            LongPressSection(settings.longPressMs, onLongPressChange)
            HapticSection(settings.hapticFeedback, onHapticChange)

            NavigationRow(R.string.about, onOpenAbout)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ServiceStatusCard(enabled: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (enabled) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (enabled) R.string.service_enabled else R.string.service_disabled_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.service_disabled_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onOpen) {
                Text(stringResource(R.string.open_accessibility_settings))
            }
        }
    }
}

@Composable
private fun PowerRestrictionCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.power_restriction_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.power_restriction_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpen) {
                Text(stringResource(R.string.open_app_info))
            }
        }
    }
}

@Composable
private fun ActionSection(
    title: String,
    selected: KeyAction,
    portrait: TapPoint?,
    landscape: TapPoint?,
    onSelect: (KeyAction) -> Unit,
    onRegister: () -> Unit,
    onClear: (ScreenOrientation) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                ActionRadio(R.string.action_normal, KeyAction.NORMAL, selected, onSelect)
                ActionRadio(R.string.action_tap, KeyAction.TAP, selected, onSelect)
            }
            if (selected == KeyAction.TAP) {
                TapPointRow(R.string.tap_point_portrait, portrait) {
                    onClear(ScreenOrientation.PORTRAIT)
                }
                TapPointRow(R.string.tap_point_landscape, landscape) {
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
                if (portrait == null && landscape == null) {
                    // 座標が無いあいだは素通しに倒れるため、そのことを明示する。
                    Text(
                        text = stringResource(R.string.tap_point_unregistered),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
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
    labelRes: Int,
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
            text = stringResource(labelRes),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LongPressSection(current: Int, onSelect: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.long_press_threshold),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in AppSettings.LONG_PRESS_CHOICES) {
                    FilterChip(
                        selected = choice == current,
                        onClick = { onSelect(choice) },
                        label = { Text("$choice ms") },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(selectedCount: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.enabled_apps),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (selectedCount == 0) {
                    stringResource(R.string.enabled_apps_all)
                } else {
                    stringResource(R.string.enabled_apps_count, selectedCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PauseSection(paused: Boolean, onPausedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.pause),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = paused, onCheckedChange = onPausedChange)
            }
            Text(
                text = stringResource(R.string.pause_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun HapticSection(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.haptic_feedback),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(checked = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun NavigationRow(labelRes: Int, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}
