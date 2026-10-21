package com.ayati.volumetap.ui

/**
 * アプリを追加する画面の一括選択（design-key-options.md §3）。
 *
 * どれも [selectable]（いま一覧に出ていて、まだ割り当てのないアプリ）だけを動かし、
 * 絞り込みの外で選んだアプリはそのまま残す。
 */
object PickerSelection {

    fun selectAll(selected: Set<String>, selectable: Set<String>): Set<String> = selected + selectable

    fun clearAll(selected: Set<String>, selectable: Set<String>): Set<String> = selected - selectable

    fun invert(selected: Set<String>, selectable: Set<String>): Set<String> =
        (selected - selectable) + (selectable - selected)
}
