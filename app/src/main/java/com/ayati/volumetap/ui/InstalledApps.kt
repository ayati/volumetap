package com.ayati.volumetap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ランチャーに出るアプリ 1 件。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/** アプリ名とアイコンの取得。アプリ選択画面・設定画面・アプリ設定画面で共用する。 */
object InstalledApps {

    private const val ICON_PX = 96

    /**
     * ランチャーから起動できるアプリを列挙する（自アプリを除く）。
     *
     * `QUERY_ALL_PACKAGES` は使わない。マニフェストの `<queries>` で
     * ランチャー起動可能なアプリだけに可視性を絞っており、Play の審査でも
     * 説明が要る強い権限を避けられる。
     */
    suspend fun launchable(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // MATCH_DEFAULT_ONLY を付けると CATEGORY_DEFAULT を持たないランチャー項目
        // （Poweramp など多数）が落ちる。ランチャー自身と同じく flags は 0 で引く。
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .mapNotNull { load(pm, it) }
            .sortedBy { it.label }
            .toList()
    }

    /**
     * 指定したアプリの名前とアイコン。アンインストール済みなどで見えなければ、
     * パッケージ名をそのまま名前にする（設定を消す手段を残すため、一覧から落とさない）。
     */
    suspend fun of(context: Context, packageNames: Collection<String>): Map<String, InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packageNames.associateWith { load(pm, it) ?: InstalledApp(it, it, null) }
        }

    private fun load(pm: PackageManager, packageName: String): InstalledApp? {
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        return InstalledApp(
            packageName = packageName,
            label = pm.getApplicationLabel(info).toString(),
            icon = runCatching {
                pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull(),
        )
    }
}
