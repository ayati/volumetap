package com.ayati.volumetap.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-pwa-picker.md §3.3「検索（絞り込み）」。 */
class AppSearchTest {

    private fun matches(query: String, label: String, pkg: String = "com.example.app") =
        AppSearch.matches(query, label, pkg)

    @Test
    fun `空の検索語ではすべて残す`() {
        assertTrue(matches("", "Chrome"))
        assertTrue(matches("  ", "Chrome"))
    }

    @Test
    fun `大文字小文字を区別しない`() {
        assertTrue(matches("chr", "Chrome"))
        assertTrue(matches("CHROME", "Chrome"))
    }

    @Test
    fun `全角英数でも一致する`() {
        assertTrue(matches("ｃｈｒ", "Chrome"))
    }

    @Test
    fun `ひらがなとカタカナを区別しない`() {
        assertTrue(matches("ふぃーど", "フィードリーダー"))
        assertTrue(matches("ニュース", "にゅーす速報"))
    }

    @Test
    fun `半角カナでも一致する`() {
        assertTrue(matches("ﾆｭｰｽ", "ニュース"))
    }

    @Test
    fun `パッケージ名でも一致する`() {
        assertTrue(matches("twitter", "X", "com.twitter.android"))
    }

    @Test
    fun `含まれなければ落とす`() {
        assertFalse(matches("feed", "Chrome", "com.android.chrome"))
    }
}
