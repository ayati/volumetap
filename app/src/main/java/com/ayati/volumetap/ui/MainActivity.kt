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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
                // 読み込み前（null）は同意の確認を出さない。出すと同意済みの人にも一瞬見える。
                val consent by repository.accessibilityConsent.collectAsState(initial = null)
                // 一覧に出すアプリ名とアイコン。割り当てのあるアプリが変わったら取り直す。
                val apps by produceState(emptyMap<String, InstalledApp>(), settings.profiles.keys) {
                    value = InstalledApps.of(this@MainActivity, settings.profiles.keys)
                }
                SettingsScreen(
                    settings = settings,
                    serviceEnabled = serviceEnabled.value,
                    powerRestricted = powerRestricted.value,
                    accessibilityConsent = consent,
                    onAgreeToAccessibility = { openSettings ->
                        lifecycleScope.launch { repository.setAccessibilityConsent() }
                        if (openSettings) openAccessibilitySettings()
                    },
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenAppInfo = ::openAppInfo,
                    apps = apps,
                    onEditProfile = { pkg ->
                        startActivity(ProfileEditActivity.intent(this, pkg))
                    },
                    removeProfile = repository::removeProfile,
                    removeAllProfiles = repository::removeAllProfiles,
                    restoreProfiles = repository::restoreProfiles,
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
                    onChordToggleChange = {
                        lifecycleScope.launch { repository.setChordToggle(it) }
                    },
                    onSwapKeysChange = {
                        lifecycleScope.launch { repository.setSwapKeys(it) }
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
    accessibilityConsent: Boolean?,
    onAgreeToAccessibility: (openSettings: Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    apps: Map<String, InstalledApp>,
    onEditProfile: (String?) -> Unit,
    removeProfile: suspend (String) -> AppProfile?,
    removeAllProfiles: suspend () -> Map<String, AppProfile>,
    restoreProfiles: suspend (Map<String, AppProfile>) -> Unit,
    onAddApp: () -> Unit,
    onLongPressChange: (Int) -> Unit,
    onHapticChange: (Boolean) -> Unit,
    onPausedChange: (Boolean) -> Unit,
    onChordToggleChange: (Boolean) -> Unit,
    onSwapKeysChange: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    // 「設定を開く」から出したときは、有効でも同意のあとに設定画面を開く（オフにしたくて押した人がいる）。
    var consentFromButton by rememberSaveable { mutableStateOf(false) }
    // 同意するまでは、起動するたびに 1 回だけ出す。「同意しない」のあとは「設定を開く」を押すまで出さない。
    var consentAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(accessibilityConsent) {
        if (accessibilityConsent == false && !consentAsked) {
            consentAsked = true
            consentFromButton = false
            showConsent = true
        }
    }

    /**
     * 「元に戻す」を出す（design-pwa-picker.md §9.5）。前の Snackbar は閉じる。閉じないと順番待ちになり、
     * 目の前の削除を取り消せない。
     */
    fun offerUndo(message: String, undoLabel: String, removed: Map<String, AppProfile>) {
        if (removed.isEmpty()) return
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbar.showSnackbar(message, undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) restoreProfiles(removed)
        }
    }

    val undoLabel = stringResource(R.string.undo)
    val removedOne = stringResource(R.string.profile_removed)
    val removedMany = stringResource(R.string.profiles_removed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // すべて削除は普段の画面に出さず、メニューの中に置く（§9.4）。1 件ならフリックで足りる。
                    if (settings.profiles.size >= 2) {
                        OverflowMenu(onDeleteAll = { confirmDeleteAll = true })
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (confirmDeleteAll) {
            DeleteAllDialog(
                labels = settings.profiles.keys.map { apps[it]?.label ?: it }.sorted(),
                onConfirm = {
                    confirmDeleteAll = false
                    scope.launch {
                        val removed = removeAllProfiles()
                        offerUndo(removedMany.format(removed.size), undoLabel, removed)
                    }
                },
                onDismiss = { confirmDeleteAll = false },
            )
        }
        if (showConsent) {
            val openSettings = consentFromButton || !serviceEnabled
            AccessibilityConsentDialog(
                opensSettings = openSettings,
                onAgree = {
                    showConsent = false
                    onAgreeToAccessibility(openSettings)
                },
                onDecline = { showConsent = false },
            )
        }
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
            ServiceStatusCard(serviceEnabled) {
                // 同意の前に設定画面へは送らない（design.md §6.1.3）。
                // 読み込み前（null）は何もしない。同意済みの人にダイアログを出さないため。
                when (accessibilityConsent) {
                    true -> onOpenAccessibilitySettings()
                    false -> {
                        consentFromButton = true
                        showConsent = true
                    }
                    null -> Unit
                }
            }

            SectionHeader(R.string.section_assignment)
            Text(
                text = stringResource(R.string.model_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.swapKeys) SwapKeysNotice()
            val sorted = settings.profiles.entries.sortedBy { (pkg, _) -> apps[pkg]?.label ?: pkg }
            for ((pkg, profile) in sorted) {
                // key が無いと、1 件消したときにフリックの状態が隣の行に引き継がれる。
                key(pkg) {
                    val label = apps[pkg]?.label ?: pkg
                    SwipeToDeleteRow(
                        onDelete = {
                            scope.launch {
                                removeProfile(pkg)?.let { offerUndo(removedOne.format(label), undoLabel, mapOf(pkg to it)) }
                            }
                        },
                    ) {
                        ProfileRow(
                            app = apps[pkg],
                            label = label,
                            profile = profile,
                            onClick = { onEditProfile(pkg) },
                        )
                    }
                }
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
            SwitchSection(R.string.pause, R.string.pause_body, settings.paused, onPausedChange)
            SwitchSection(R.string.chord_toggle, R.string.chord_toggle_body, settings.chordToggle, onChordToggleChange)

            SectionHeader(R.string.section_advanced)
            LongPressSection(settings.longPressMs, onLongPressChange)
            HapticSection(settings.hapticFeedback, onHapticChange)
            SwitchSection(R.string.swap_keys, R.string.swap_keys_body, settings.swapKeys, onSwapKeysChange)

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

/**
 * ユーザー補助の利用目的の開示と同意（design.md §6.1.3。Play のユーザー補助 API のポリシー）。
 *
 * 画面の外や戻るでは閉じない。閉じただけでは同意にならないので、どちらかのボタンを選んでもらう。
 * [opensSettings] が false のとき（すでに有効な人に起動時に出したとき）は、設定画面を開かずに同意だけを受ける。
 */
@Composable
private fun AccessibilityConsentDialog(opensSettings: Boolean, onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.consent_title)) },
        text = {
            // 文字を大きくしている端末でもボタンが押し出されないよう、本文だけをスクロールさせる。
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.consent_body))
                if (opensSettings) {
                    Text(
                        text = stringResource(R.string.consent_next),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text(stringResource(if (opensSettings) R.string.consent_agree_open else R.string.consent_agree))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(R.string.consent_decline)) }
        },
    )
}

/**
 * 左へのフリックで消せる行（design-pwa-picker.md §9.3）。
 *
 * 右へのフリックは有効にしない。左端から右へなぞる動きは、ジェスチャナビの「戻る」と重なる。
 * フリックできない人のために、読み上げの操作メニューにも「削除」を出す。
 */
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        // 部品の既定（56dp）は短く、縦にスクロールするつもりの斜めの動きでも消えやすい。
        positionalThreshold = { width -> width * 0.4f },
    )
    val deleteLabel = stringResource(R.string.delete)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete() },
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
        },
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = deleteLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        content()
    }
}

/** 上のバーの「⋮」。割り当てをすべて削除する入口だけを持つ。 */
@Composable
private fun OverflowMenu(onDeleteAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_all_profiles)) },
                onClick = {
                    expanded = false
                    onDeleteAll()
                },
            )
        }
    }
}

/**
 * すべて削除の確認（design-pwa-picker.md §9.4）。件数と、アプリ名を最大 3 件見せる。
 * 出すのは 2 件以上のときだけなので、件数の文言は常に複数でよい。
 */
@Composable
private fun DeleteAllDialog(labels: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val names = labels.take(3).joinToString(stringResource(R.string.name_separator)).let {
        if (labels.size > 3) stringResource(R.string.delete_all_names_more, it) else it
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_all_title)) },
        text = { Text(stringResource(R.string.delete_all_message, labels.size, names)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.delete_all_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
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

/** 見出し・スイッチ・説明文のカード。 */
@Composable
private fun SwitchSection(titleRes: Int, bodyRes: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    // 長い見出しでもスイッチを押し出さない。
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            Text(
                text = stringResource(bodyRes),
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
