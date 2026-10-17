package com.ayati.volumetap.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ayati.volumetap.R
import com.ayati.volumetap.data.AppSettings
import com.ayati.volumetap.data.SettingsRepository
import kotlinx.coroutines.launch

/**
 * アプリごとの割り当てを追加する画面（design-pageturn.md §7.1）。
 *
 * 選んだアプリに割り当てが無ければ初期値（−で送る／＋で戻す）で作り、
 * そのアプリの設定画面へ進む。設定済みのアプリを選んだときは設定画面を開くだけ。
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
                    value = InstalledApps.launchable(this@AppPickerActivity)
                }
                AppPickerScreen(
                    onBack = ::finish,
                    apps = apps,
                    configured = settings.profiles.keys,
                    onPick = { pkg ->
                        lifecycleScope.launch {
                            if (pkg !in settings.profiles) {
                                // 変更しない transform で、初期値のプロファイルを作る。
                                repository.updateProfile(pkg) { it }
                            }
                            startActivity(ProfileEditActivity.intent(this@AppPickerActivity, pkg))
                            finish()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerScreen(
    onBack: () -> Unit,
    apps: List<InstalledApp>?,
    configured: Set<String>,
    onPick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_app)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            // PWA は WebAPK としてブラウザのプロセス上で動くため、この一覧に現れない。
            // 気づく手がかりが無いと詰まるので明示する。
            Text(
                text = stringResource(R.string.app_picker_pwa_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            // ホーム画面の左（-1 画面）の Discover はランチャーの上に重なった Google アプリの
            // ウィンドウで、前面アプリは Google アプリとして観測される。名前からは分からない。
            Text(
                text = stringResource(R.string.app_picker_discover_hint),
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            configured = app.packageName in configured,
                            onClick = { onPick(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, configured: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (configured) {
            Text(
                text = stringResource(R.string.app_picker_configured),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun AppIcon(app: InstalledApp?) {
    val icon = app?.icon
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
    } else {
        Spacer(Modifier.size(40.dp))
    }
}
