package com.ayati.volumetap.data

import androidx.annotation.RawRes
import com.ayati.volumetap.R

/** ライセンス種別。全文は `res/raw/` に同梱し、外部リンクで代替しない（設計書 §13.2）。 */
enum class LicenseType(val displayName: String, @param:RawRes val textRes: Int) {
    APACHE_2_0("Apache License 2.0", R.raw.license_apache_2_0),
    MIT("MIT License", R.raw.license_mit),
}

data class LibraryNotice(
    val name: String,
    val holder: String,
    val license: LicenseType,
)

/**
 * APK に同梱するサードパーティ成果物（設計書 §13.2）。
 *
 * ⚠️ **依存を 1 つ追加したら、ここに 1 行追加する。**
 * Apache-2.0 §4(a) は再頒布物の受領者にライセンスの写しを渡すことを要求しており、
 * このリストの更新漏れはそのまま表示義務違反になる。
 * 静的リストの唯一の弱点なので、リリース前チェックリストにも入れてある（§13.5）。
 *
 * GPL / LGPL / AGPL 系のライブラリは採用しない（§13.4）。
 */
val THIRD_PARTY_LIBRARIES = listOf(
    LibraryNotice(
        name = "Kotlin Standard Library",
        holder = "JetBrains s.r.o. and Kotlin Programming Language contributors",
        license = LicenseType.APACHE_2_0,
    ),
    LibraryNotice(
        name = "kotlinx.coroutines",
        holder = "JetBrains s.r.o.",
        license = LicenseType.APACHE_2_0,
    ),
    LibraryNotice(
        name = "AndroidX (Core, Activity, Lifecycle, DataStore)",
        holder = "The Android Open Source Project",
        license = LicenseType.APACHE_2_0,
    ),
    LibraryNotice(
        name = "Jetpack Compose / Material 3",
        holder = "The Android Open Source Project",
        license = LicenseType.APACHE_2_0,
    ),
)
