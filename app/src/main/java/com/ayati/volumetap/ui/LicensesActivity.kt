package com.ayati.volumetap.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayati.volumetap.R
import com.ayati.volumetap.data.LibraryNotice
import com.ayati.volumetap.data.LicenseType
import com.ayati.volumetap.data.THIRD_PARTY_LIBRARIES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * オープンソースライセンス画面（設計書 §6.3）。
 *
 * 同梱している Apache-2.0 ライブラリの表示義務を満たすための画面。
 * ライセンス全文は `res/raw/` に同梱しており、ネットワークアクセスは発生しない。
 */
class LicensesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VolumeTapTheme {
                LicensesScreen(onBack = ::finish)
            }
        }
    }
}

private suspend fun Context.readRawText(resId: Int): String = withContext(Dispatchers.IO) {
    resources.openRawResource(resId).bufferedReader().use { it.readText() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OwnLicenseCard() }

            item {
                Text(
                    text = stringResource(R.string.licenses_libraries),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(THIRD_PARTY_LIBRARIES) { LibraryCard(it) }

            item {
                LicenseTextCard(
                    license = LicenseType.APACHE_2_0,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun OwnLicenseCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = LicenseType.MIT.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.copyright),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.name_and_icon_reserved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            ExpandableLicenseText(LicenseType.MIT)
        }
    }
}

@Composable
private fun LibraryCard(notice: LibraryNotice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = notice.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = notice.holder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = notice.license.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ライセンス種別ごとの全文カード。同じ種別の全文は 1 回だけ載せれば足りる。 */
@Composable
private fun LicenseTextCard(license: LicenseType, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = license.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            ExpandableLicenseText(license)
        }
    }
}

@Composable
private fun ExpandableLicenseText(license: LicenseType) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.hide_full_text else R.string.show_full_text,
                license.displayName,
            ),
        )
    }

    if (expanded) {
        val text by produceState(initialValue = "", license) {
            value = context.readRawText(license.textRes)
        }
        HorizontalDivider()
        // 原文のまま表示する（整形もパースもしない）。等幅で、横は独立してスクロールさせる。
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}
