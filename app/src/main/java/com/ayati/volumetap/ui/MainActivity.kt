package com.ayati.volumetap.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppProfile
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * 設定画面（design-pageturn.md §7.1）。
 *
 * アプリごとの割り当ての一覧を最上部（サービス状態カードの直下）に置く。
 * 設定を開く目的のほとんどは割り当ての変更なので、最初の 1 画面に収める。
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
                // 一覧に出すアプリ名とアイコン。割り当てのあるアプリが変わったら取り直す。
                val apps by produceState(emptyMap<String, InstalledApp>(), settings.profiles.keys) {
                    value = InstalledApps.of(this@MainActivity, settings.profiles.keys)
                }
                SettingsScreen(
                    settings = settings,
                    serviceEnabled = serviceEnabled.value,
                    powerRestricted = powerRestricted.value,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenAppInfo = ::openAppInfo,
                    apps = apps,
                    onEditProfile = { pkg ->
                        startActivity(ProfileEditActivity.intent(this, pkg))
                    },
                    onAddApp = {
                        startActivity(Intent(this, AppPickerActivity::class.java))
                    },
                    onLongPressChange = {
                        lifecycleScope.launch { repository.setLongPressMs(it) }
                    },
                    onHapticChange = {
                        lifecycleScope.launch { repository.setHapticFeedback(it) }
                    },
                    onPausedChange = {
                        lifecycleScope.launch { repository.setPaused(it) }
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
        serviceEnabled.value = isVolumeTapServiceEnabled()
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
    apps: Map<String, InstalledApp>,
    onEditProfile: (String?) -> Unit,
    onAddApp: () -> Unit,
    onLongPressChange: (Int) -> Unit,
    onHapticChange: (Boolean) -> Unit,
    onPausedChange: (Boolean) -> Unit,
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
            val sorted = settings.profiles.entries.sortedBy { (pkg, _) -> apps[pkg]?.label ?: pkg }
            for ((pkg, profile) in sorted) {
                ProfileRow(
                    app = apps[pkg],
                    label = apps[pkg]?.label ?: pkg,
                    profile = profile,
                    onClick = { onEditProfile(pkg) },
                )
            }
            if (settings.profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_profiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onAddApp) {
                Text(stringResource(R.string.add_app))
            }
            ProfileRow(
                app = null,
                label = stringResource(R.string.default_profile),
                profile = settings.defaultProfile,
                onClick = { onEditProfile(null) },
            )

            SectionHeader(R.string.section_scope)
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

/** アプリ 1 件の割り当ての要約。押すとそのアプリの設定画面を開く。 */
@Composable
private fun ProfileRow(app: InstalledApp?, label: String, profile: AppProfile, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (app != null) AppIcon(app)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        R.string.profile_summary,
                        stringResource(actionShortLabel(profile.volumeDown.action)),
                        stringResource(actionShortLabel(profile.volumeUp.action)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (profile.usesDrag && (profile.dragPortrait != null || profile.dragLandscape != null)) {
                Text(
                    text = stringResource(R.string.drag_lane_tuned_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                        // 単位は見出しに出す。「500 ms」だと幅の狭い端末で 4 つ目が収まらない。
                        label = { Text("$choice") },
                    )
                }
            }
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
