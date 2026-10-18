package com.ayati.volumetap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文字列リソースの言語ごとのそろい（design-i18n.md §7.1）。
 *
 * lint の MissingTranslation は言語単位で見るので、簡体字（b+zh+Hans）と繁体字（b+zh+Hant）の
 * どちらか一方にしか無い文字列を見逃す（どちらも「zh」として扱われる）。ここで言語のフォルダごとに確かめる。
 *
 * 単体テストの作業ディレクトリはモジュール（app/）なので、res を相対パスで読む。
 */
class StringResourcesTest {

    private val res = File("src/main/res")

    private val locales = listOf("values-ja", "values-b+zh+Hans", "values-b+zh+Hant")

    /** name → 値。translatable="false" のものは除く（訳さない文字列）。 */
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private val default = strings("values")

    @Test
    fun `どの言語も既定の言語と同じ文字列をそろえている`() {
        for (dir in locales) {
            val keys = strings(dir).keys
            assertEquals("$dir に無い文字列", emptySet<String>(), default.keys - keys)
            assertEquals("$dir にだけある文字列（訳さない文字列を含む）", emptySet<String>(), keys - default.keys)
        }
    }

    @Test
    fun `書式指定子は言語ごとに同じ`() {
        val spec = Regex("""%\d+\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")
        fun specs(s: String) = spec.findAll(s).map { it.value }.sorted().toList()
        for (dir in locales) {
            for ((name, value) in strings(dir)) {
                val expected = default[name] ?: continue
                assertEquals("$dir の $name", specs(expected), specs(value))
            }
        }
    }

    /**
     * 簡体字と繁体字の取り違え。片方にしか無い字形の組を、このアプリの文言で使う字から選んである。
     * 文字の変換を下敷きにしたときの取りこぼしや、ファイルの取り違えを拾う（§7.1）。
     */
    @Test
    fun `簡体字と繁体字の字形が混ざっていない`() {
        val pairs = listOf(
            '应' to '應', '设' to '設', '动' to '動', '击' to '擊', '开' to '開', '关' to '關',
            '时' to '時', '页' to '頁', '键' to '鍵', '调' to '調', '线' to '線', '离' to '離',
            '储' to '儲', '围' to '圍', '认' to '認', '务' to '務', '无' to '無', '碍' to '礙',
            '后' to '後', '启' to '啟', '电' to '電', '权' to '權', '览' to '覽', '码' to '碼',
            '许' to '許', '证' to '證', '图' to '圖', '标' to '標', '隐' to '隱', '网' to '網',
            '访' to '訪', '问' to '問', '读' to '讀', '显' to '顯', '发' to '發', '长' to '長',
            '载' to '載', '单' to '單', '个' to '個', '横' to '橫', '竖' to '豎', '点' to '點',
            '触' to '觸', '馈' to '饋', '态' to '態', '执' to '執', '运' to '運',
        )
        val hans = strings("values-b+zh+Hans").values.joinToString()
        val hant = strings("values-b+zh+Hant").values.joinToString()
        val traditionalInHans = pairs.map { it.second }.filter { it in hans }
        val simplifiedInHant = pairs.map { it.first }.filter { it in hant }
        assertTrue("簡体字に繁体字: $traditionalInHans", traditionalInHans.isEmpty())
        assertTrue("繁体字に簡体字: $simplifiedInHant", simplifiedInHant.isEmpty())
    }
}
