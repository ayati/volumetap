package com.ayati.volumetap.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.ayati.volumetap.R

/**
 * `TopAppBar` の戻るボタン。
 *
 * システムの戻るジェスチャだけでも戻れるが、画面上に手がかりが無いと不親切なので
 * 子画面には必ず置く。
 *
 * アイコンは `material-icons` ライブラリではなく自前のベクターを使う。
 * 依存を 1 つ増やすとライセンス表示の維持対象も 1 つ増えるため（設計書 §13.4）。
 */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.back),
        )
    }
}
