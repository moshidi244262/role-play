package com.moyer.roleplay.core

import com.moyer.roleplay.data.TextRule
import com.google.re2j.Pattern
import com.google.re2j.Matcher

data class RuleResult(val text: String, val warnings: List<String> = emptyList())

object RegexEngine {
    private class Limit : RuntimeException()
    private class Budget {
        var steps = 0
        val deadline = System.nanoTime() + 40_000_000
        fun check() { if (++steps > 200000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted) throw Limit() }
    }
    data class Compiled(val pattern: Pattern, val global: Boolean)
    fun compile(value: String): Compiled {
        require(value.length <= 4096 && value.isNotEmpty()) { "查找表达式不能为空或超过 4096 字符" }
        var source = value; var flags = ""
        if (value.startsWith('/')) {
            val end = value.lastIndexOf('/')
            require(end > 0) { "正则缺少结束斜线" }
            source = value.substring(1, end); flags = value.substring(end + 1)
            require(flags.all { it in "gims" } && flags.toSet().size == flags.length) { "目前支持 g、i、m、s 标志；该规则包含不支持的标志" }
        }
        var options = 0
        if ('i' in flags) options = options or Pattern.CASE_INSENSITIVE
        if ('m' in flags) options = options or Pattern.MULTILINE
        if ('s' in flags) options = options or Pattern.DOTALL
        val pattern=try {Pattern.compile(source,options)} catch(_: com.google.re2j.PatternSyntaxException) {
            throw IllegalArgumentException("表达式语法不兼容：请检查前后查找、模式内反向引用或括号")
        }
        require(pattern.programSize()<=8192) {"表达式过于复杂，请拆分规则"}
        return Compiled(pattern, 'g' in flags)
    }

    fun compileRule(rule: TextRule,charName: String="角色",userName: String="玩家"): Compiled {
        fun name(value: String)=if(rule.substituteFind==2) Pattern.quote(value) else value
        val pattern=if(rule.substituteFind==0) rule.pattern else rule.pattern.replace("{{char}}",name(charName)).replace("{{user}}",name(userName))
        return compile(pattern)
    }

    fun matches(pattern: String, text: String): Boolean = try {
        val budget=Budget()
        val result=compile(pattern).pattern.matcher(text.take(200000)).find()
        budget.check();result
    } catch (_: StackOverflowError) { false } catch (_: Exception) { false }

    fun apply(text: String, rules: List<TextRule>, target: String, display: Boolean, depth: Int = 0,
        charName: String = "", userName: String = "玩家"): RuleResult {
        var result = text
        val warnings = mutableListOf<String>()
        if (text.length > 200000) return RuleResult(text, listOf("文字超过正则处理上限，保留原文"))
        fun macros(value: String, escape: Boolean = false): String {
            fun name(s: String) = if (escape) Pattern.quote(s) else s
            return value.replace("{{char}}", name(charName)).replace("{{user}}", name(userName))
        }
        val enabled=rules.filter {it.enabled}
        if(enabled.size>32) warnings+="已绑定超过 32 条启用正则，仅执行前 32 条"
        for (rule in enabled.take(32)) {
            if (!rule.enabled || target !in rule.targets || (display && !rule.display) || (!display && !rule.prompt) ||
                depth < rule.minDepth || (rule.maxDepth >= 0 && depth > rule.maxDepth)) continue
            try {
                val pattern = compileRule(rule,charName,userName)
                val budget = Budget()
                val matcher = pattern.pattern.matcher(result)
                val output = StringBuilder(); var previous = 0
                while (matcher.find()) {
                    budget.check()
                    output.append(result, previous, matcher.start())
                    var match = matcher.group()
                    for (trim in rule.trims) match = match.replace(macros(trim), "")
                    val replacement = macros(rule.replacement)
                    output.append(expand(replacement, matcher, result, match))
                    previous = matcher.end()
                    if (output.length > 1000000) throw Limit()
                    if (!pattern.global) break
                }
                budget.check()
                output.append(result, previous, result.length)
                if (output.length > 1000000) throw Limit()
                result = output.toString()
            } catch (_: Limit) { warnings += "${rule.name}：匹配超出预算，保留原文" }
            catch (_: StackOverflowError) { warnings += "${rule.name}：表达式嵌套过深，保留原文" }
            catch (_: Exception) { warnings += "${rule.name}：表达式不兼容，保留原文" }
        }
        return RuleResult(result, warnings)
    }

    private fun expand(value: String, matcher: Matcher, original: String, trimmed: String): String = buildString {
        var i = 0
        while (i < value.length) {
            if (value.startsWith("{{match}}", i)) { append(trimmed); i += 9; continue }
            if (value[i] != '$' || i + 1 >= value.length) { append(value[i++]); continue }
            val next = value[i + 1]
            when (next) {
                '$' -> { append('$'); i += 2 }
                '&' -> { append(matcher.group()); i += 2 }
                '`' -> { append(original, 0, matcher.start()); i += 2 }
                '\'' -> { append(original, matcher.end(), original.length); i += 2 }
                in '1'..'9' -> {
                    var number = next.digitToInt(); var length = 2
                    if (i + 2 < value.length && value[i + 2].isDigit()) {
                        val two = number * 10 + value[i + 2].digitToInt()
                        if (two <= matcher.groupCount()) { number = two; length = 3 }
                    }
                    if (number <= matcher.groupCount()) { append(matcher.group(number) ?: ""); i += length }
                    else { append('$'); i++ }
                }
                else -> { append('$'); i++ }
            }
        }
    }
}
