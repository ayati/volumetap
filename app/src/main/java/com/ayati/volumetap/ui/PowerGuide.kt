package com.ayati.volumetap.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import com.ayati.volumetap.R

/** 省電力の案内（設計書 §8）。出さなくてよいときは [powerGuide] が null を返す。 */
internal sealed interface PowerGuide {
    /** HyperOS / MIUI。自動起動が公開 API で読めないので、項目を並べずに文章で案内する（§8.1）。 */
    data object Xiaomi : PowerGuide

    /** 項目ごとに済んでいるかを並べる（§8.2）。未設定の項目が 1 つでもあるときだけ作る。 */
    data class Checklist(val items: List<Pair<PowerItem, Boolean>>) : PowerGuide
}

internal enum class PowerItem(@StringRes val label: Int) {
    BACKGROUND_USAGE(R.string.power_item_background),
    SAMSUNG_BATTERY(R.string.power_item_samsung_battery),
    SAMSUNG_UNUSED(R.string.power_item_samsung_unused),
}

internal val isSamsung: Boolean
    get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/**
 * 裏で止められうる設定が残っているか。設定アプリから戻るたびに評価し直す。
 *
 * - Xiaomi: 「電源」の「制限なし」は Android 標準の省電力除外にも反映されるので、それで判定する（§8.1）
 * - Samsung: バッテリー「制限なし」（省電力除外）と、「使用していない場合アプリを管理」のオフ
 * - そのほか: AOSP の「バックグラウンドでの使用を許可」。既定でオンなので、ほとんどの端末では出ない
 */
internal fun Context.powerGuide(): PowerGuide? {
    val power = getSystemService(PowerManager::class.java) ?: return null
    if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
        return if (power.isIgnoringBatteryOptimizations(packageName)) null else PowerGuide.Xiaomi
    }
    val items = buildList {
        if (isSamsung) {
            add(PowerItem.SAMSUNG_BATTERY to power.isIgnoringBatteryOptimizations(packageName))
            // API 30 未満には「使用していない場合アプリを管理」が無い。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(PowerItem.SAMSUNG_UNUSED to packageManager.isAutoRevokeWhitelisted)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val activity = getSystemService(ActivityManager::class.java)
            if (activity != null) add(PowerItem.BACKGROUND_USAGE to !activity.isBackgroundRestricted)
        }
    }
    return if (items.all { it.second }) null else PowerGuide.Checklist(items)
}
