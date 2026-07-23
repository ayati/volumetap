package com.ayati.volumetap.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ランチャーに出るアプリ 1 件。 */
private data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * 「有効にするアプリ」の選択画面（設計書 §5.4）。
 *
 * ひとつも選ばなければ全アプリで有効という既定の挙動は変えない。
 * 選んだ場合のみ、そのアプリが前面のときだけキーを横取りする。
 */
class AppPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = SettingsRepository(this)
        setContent {
            VolumeTapTheme {
                val settings by repository.settings.collectAsState(initial = AppSettings())
                val apps by produceState<List<InstalledApp>?>(initialValue = null) {
                    value = loadInstalledApps()
                }
                AppPickerScreen(
                    onBack = ::finish,
                    apps = apps,
                    selected = settings.enabledPackages,
                    onToggle = { pkg, checked ->
                        val next = settings.enabledPackages.toMutableSet()
                        if (checked) next.add(pkg) else next.remove(pkg)
                        lifecycleScope.launch { repository.setEnabledPackages(next) }
                    },
                    onClearAll = {
                        lifecycleScope.launch { repository.setEnabledPackages(emptySet()) }
                    },
                )
            }
        }
    }

    /**
     * ランチャーから起動できるアプリを列挙する。
     *
     * `QUERY_ALL_PACKAGES` は使わない。マニフェストの `<queries>` で
     * ランチャー起動可能なアプリだけに可視性を絞っており、Play の審査でも
     * 説明が要る強い権限を避けられる。
     */
    private suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = runCatching {
                        pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
                    }.getOrNull(),
                )
            }
            .sortedBy { it.label }
            .toList()
    }

    private companion object {
        const val ICON_PX = 96
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    onBack: () -> Unit,
    apps: List<InstalledApp>?,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClearAll: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.enabled_apps)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = onClearAll) {
                            Text(stringResource(R.string.clear_all))
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Text(
                text = stringResource(
                    if (selected.isEmpty()) {
                        R.string.app_picker_all
                    } else {
                        R.string.app_picker_selected
                    },
                    selected.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            // PWA は WebAPK としてブラウザのプロセス上で動くため、この一覧に現れない。
            // 気づく手がかりが無いと詰まるので明示する。
            Text(
                text = stringResource(R.string.app_picker_pwa_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
            HorizontalDivider()

            if (apps == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // 選択済みだが一覧に無いパッケージ（アンインストール済みなど）。
                // ここで出さないと個別に解除する手段が無くなる。
                val listed = apps.mapTo(HashSet()) { it.packageName }
                val orphans = selected.filterNot { it in listed }.sorted()

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (orphans.isNotEmpty()) {
                        item {
                            SectionHeader(
                                titleRes = R.string.app_picker_orphans,
                                bodyRes = R.string.app_picker_orphans_body,
                            )
                        }
                        items(orphans, key = { "orphan:$it" }) { pkg ->
                            OrphanRow(packageName = pkg, onClear = { onToggle(pkg, false) })
                        }
                        item { HorizontalDivider() }
                    }
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in selected,
                            onCheckedChange = { onToggle(app.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Spacer(Modifier.size(40.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(titleRes: Int, bodyRes: Int) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OrphanRow(packageName: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClear)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.size(40.dp))
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = true, onCheckedChange = { onClear() })
    }
}
