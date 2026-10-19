package com.ayati.volumetap.ui

import java.text.Normalizer
import java.util.Locale

/**
 * アプリを追加する画面の絞り込み（design-pwa-picker.md §3.3）。
 *
 * 全角・半角、大文字・小文字、ひらがな・カタカナの違いを無視して、
 * アプリ名かパッケージ名に検索語が含まれるかを見る。
 */
object AppSearch {

    fun matches(query: String, label: String, packageName: String): Boolean {
        val q = normalize(query.trim())
        if (q.isEmpty()) return true
        return q in normalize(label) || q in normalize(packageName)
    }

    /** NFKC → 小文字 → ひらがなをカタカナに。 */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .map { if (it in HIRAGANA) it + KATAKANA_OFFSET else it }
            .joinToString("")

    /** ぁ（U+3041）〜ゖ（U+3096）。カタカナとは一定の差で並んでいる。 */
    private val HIRAGANA = 'ぁ'..'ゖ'
    private const val KATAKANA_OFFSET = 0x60
}
