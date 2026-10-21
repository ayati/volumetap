package com.ayati.volumetap.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ayati.volumetap.R

/** 上下を入れ替えているときの注意書き（design-key-options.md §1.3）。設定画面とアプリの設定画面で使う。 */
@Composable
internal fun SwapKeysNotice() {
    Text(
        text = stringResource(R.string.swap_keys_notice),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
