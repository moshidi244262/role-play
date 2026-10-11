package com.moyer.roleplay.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class MessageRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)
sealed interface MessageBlock {
    data class Paragraph(val runs: List<MessageRun>) : MessageBlock
    data class Disclosure(val title: String, val children: List<MessageBlock>, val open: Boolean) : MessageBlock
}

/** Only text and structural markup become native UI. Attributes, CSS, URLs and scripts never execute. */
object MessageMarkup {
    private val tag = Regex("</?[a-zA-Z][\\w:-]*(?:\\s|/?>)")
    private val boundaries = setOf("div", "p", "section", "article", "header", "footer", "blockquote", "pre", "ul", "ol", "table", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "hr")

    fun parse(raw: String): List<MessageBlock> {
        if (!tag.containsMatchIn(raw)) return listOf(MessageBlock.Paragraph(listOf(MessageRun(raw))))
        var source = raw.take(500_000).replace(Regex("(?m)^\\s*```(?:html)?\\s*$"), "")
        // Do not flash unfinished attributes as code while a stream is receiving a tag.
        val unfinished = source.lastIndexOf('<')
        if (unfinished > source.lastIndexOf('>') && Regex("^</?[a-zA-Z]").containsMatchIn(source.substring(unfinished))) source = source.substring(0, unfinished)
        val body = Jsoup.parseBodyFragment(source).body()
        body.select("script,style,iframe,object,embed,form,input,link,meta,base,noscript,svg,math").remove()
        var remaining = 6000
        fun render(nodes: List<Node>, depth: Int): List<MessageBlock> {
            val result = mutableListOf<MessageBlock>()
            val runs = mutableListOf<MessageRun>()
            fun flush() {
                if (runs.any { it.text.isNotBlank() }) {
                    val cleaned = runs.toMutableList()
                    cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart())
                    cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
                    result += MessageBlock.Paragraph(cleaned.filter { it.text.isNotEmpty() })
                }
                runs.clear()
            }
            fun walk(node: Node, level: Int, bold: Boolean = false, italic: Boolean = false, code: Boolean = false) {
                if (remaining-- <= 0 || level > 32) return
                if (node is TextNode) {
                    runs += MessageRun(node.wholeText, bold, italic, code)
                    return
                }
                if (node !is Element) return
                val name = node.normalName()
                if (name == "details") {
                    flush()
                    val summary = node.children().firstOrNull { it.normalName() == "summary" }
                    result += MessageBlock.Disclosure(summary?.text()?.ifBlank { "状态信息" } ?: "状态信息",
                        render(node.childNodes().filter { it !== summary }, level + 1), node.hasAttr("open"))
                    return
                }
                if (name == "br") { runs += MessageRun("\n"); return }
                if (name == "img") {
                    node.attr("alt").takeIf { it.isNotBlank() }?.let { runs += MessageRun("［$it］") }
                    return
                }
                val boundary = name in boundaries || name == "li"
                if (boundary) flush()
                if (name == "li") runs += MessageRun("• ")
                if (name in setOf("td", "th") && runs.any { it.text.isNotBlank() }) runs += MessageRun(" · ")
                node.childNodes().forEach { walk(it, level + 1,
                    bold || name in setOf("b", "strong", "th", "h1", "h2", "h3", "summary"),
                    italic || name in setOf("i", "em"), code || name in setOf("code", "pre")) }
                if (boundary) flush()
            }
            nodes.forEach { walk(it, depth) }
            flush()
            return result
        }
        return render(body.childNodes(), 0)
    }
}
