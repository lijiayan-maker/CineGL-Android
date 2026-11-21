package com.mycro.cinegl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 双语检查：系统为简体中文时显示中文，其他语言一律显示英文。
 *  - 默认资源（英文）和 values-b+zh+Hans（简体中文）的键、占位符必须一一对应
 *  - Kotlin 代码里不能再出现中文字符串字面量，否则英文界面会漏出中文（注释不算）
 * 单测的工作目录是 app/ 模块目录。
 */
class I18nTest {

    private val res = File("src/main/res")

    @Test
    fun simplifiedChineseCoversEveryEnglishString() {
        val en = strings(File(res, "values/strings.xml"), translatableOnly = true)
        val zh = strings(File(res, "values-b+zh+Hans/strings.xml"), translatableOnly = false)
        assertEquals("中文缺少的键", emptySet<String>(), en.keys - zh.keys)
        assertEquals("中文多出的键（英文里没有，或英文里标了 translatable=false）", emptySet<String>(), zh.keys - en.keys)
        for ((key, text) in en) {
            assertEquals("「$key」两种语言的占位符不一致", placeholders(text), placeholders(zh.getValue(key)))
        }
    }

    @Test
    fun localeSpecificValuesExistInBothLanguages() {
        for (type in listOf("bools", "dimens")) {
            val en = names(File(res, "values/$type.xml"))
            val zh = names(File(res, "values-b+zh+Hans/$type.xml"))
            assertEquals("$type.xml 两种语言的键不一致", en, zh)
        }
    }

    @Test
    fun noHardcodedChineseInCode() {
        val cjk = Regex("\"[^\"\\n]*[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef][^\"\\n]*\"")
        val offenders = mutableListOf<String>()
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            var inBlock = false
            file.readLines().forEachIndexed { i, line ->
                val s = line.trim()
                when {
                    inBlock -> if ("*/" in s) inBlock = false
                    s.startsWith("/*") -> inBlock = "*/" !in s
                    s.startsWith("*") -> Unit
                    cjk.containsMatchIn(stripLineComment(line)) -> offenders += "${file.path}:${i + 1}  $s"
                }
            }
        }
        assertTrue("界面文案要放进字符串资源：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun strings(file: File, translatableOnly: Boolean): Map<String, String> =
        elements(file, "string")
            .filter { !translatableOnly || it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }

    private fun names(file: File): Set<String> =
        elements(file, "*").map { it.getAttribute("name") }.filter { it.isNotEmpty() }.toSet()

    private fun elements(file: File, tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun placeholders(text: String): List<String> =
        Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z]").findAll(text).map { it.value }.sorted().toList()

    /** 去掉行尾的 // 注释（// 前面的引号数为偶数时才是注释，不是字符串里的 //）。 */
    private fun stripLineComment(line: String): String {
        var from = 0
        while (true) {
            val j = line.indexOf("//", from)
            if (j < 0) return line
            if (line.substring(0, j).count { it == '"' } % 2 == 0) return line.substring(0, j)
            from = j + 2
        }
    }
}
