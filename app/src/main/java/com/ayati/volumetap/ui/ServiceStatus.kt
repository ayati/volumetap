package com.ayati.volumetap.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.ayati.volumetap.service.VolumeTapService

/** ユーザー補助サービスが有効かどうか。設定アプリから戻るたびに評価し直す。 */
internal fun Context.isVolumeTapServiceEnabled(): Boolean {
    val manager = getSystemService(AccessibilityManager::class.java) ?: return false
    val target = "$packageName/${VolumeTapService::class.java.name}"
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.id == target || it.id.startsWith("$packageName/") }
}
