package com.ayati.volumetap.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** design-key-options.md §3.2「一括選択の対象」。 */
class PickerSelectionTest {

    /** 絞り込みの外で選んだアプリ。 */
    private val hidden = "com.example.hidden"
    private val selectable = setOf("a", "b", "c")

    @Test
    fun `すべて選択は表示中のアプリを足し絞り込みの外は残す`() {
        assertEquals(setOf(hidden, "a", "b", "c"), PickerSelection.selectAll(setOf(hidden, "a"), selectable))
    }

    @Test
    fun `すべて解除は表示中のアプリだけを外す`() {
        assertEquals(setOf(hidden), PickerSelection.clearAll(setOf(hidden, "a", "b"), selectable))
    }

    @Test
    fun `反転は表示中のアプリだけを反転する`() {
        assertEquals(setOf(hidden, "c"), PickerSelection.invert(setOf(hidden, "a", "b"), selectable))
    }

    @Test
    fun `反転を2回すると元に戻る`() {
        val selected = setOf(hidden, "b")
        assertEquals(selected, PickerSelection.invert(PickerSelection.invert(selected, selectable), selectable))
    }
}
