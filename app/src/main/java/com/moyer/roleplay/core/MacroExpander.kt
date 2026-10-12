package com.moyer.roleplay.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Request-local variables. Imported macros never execute code or mutate saved chat state. */
class MacroExpander(values: Map<String, String>, seed: Int = 0) {
    private val builtins = values.mapKeys { it.key.lowercase(Locale.ROOT) }
    private val variables = mutableMapOf<String, String>()
    private val random = Random(seed)
    private var steps = 0
    val warnings = linkedSetOf<String>()

    fun expand(text: String): String = expandText(text, 0)

    private fun expandText(text: String, level: Int): String {
        require(level <= 24 && text.length <= 1_000_000) { "宏嵌套或展开内容过大，请简化预设" }
        val out = StringBuilder()
        var cursor = 0
        while(cursor < text.length) {
            val start = text.indexOf("{{", cursor)
            if(start < 0) { out.append(text, cursor, text.length); break }
            out.append(text, cursor, start)
            var nesting = 1
            var end = start + 2
            while(end < text.length && nesting > 0) {
                when {
                    text.startsWith("{{", end) -> { nesting++; end += 2 }
                    text.startsWith("}}", end) -> { nesting--; end += 2 }
                    else -> end++
                }
            }
            if(nesting != 0) {
                warnings += "存在未闭合的宏，请检查提示内容"
                out.append(text, start, text.length); break
            }
            require(++steps <= 10000) { "宏展开次数过多，请简化预设" }
            val raw = text.substring(start + 2, end - 2)
            // Comments do not evaluate nested side effects.
            val body = if(raw.trimStart().startsWith("//")) raw else expandText(raw, level + 1)
            val parts = body.split("::")
            val first = parts.first().trim()
            val command = first.substringBefore(' ').lowercase(Locale.ROOT)
            val args = if(parts.size > 1) parts.drop(1) else first.substringAfter(' ', "").takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
            fun arg(index: Int) = args.getOrNull(index)?.trim().orEmpty()
            val replacement = when {
                body.trimStart().startsWith("//") -> ""
                command == "trim" -> {
                    while(out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
                    while(end < text.length && text[end].isWhitespace()) end++
                    ""
                }
                command == "setvar" && args.size >= 2 -> {
                    val key = arg(0)
                    require(key.length in 1..200 && variables.size <= 1000) { "预设变量名称或数量无效" }
                    variables[key] = args.drop(1).joinToString("::").trim(); ""
                }
                command == "getvar" && args.isNotEmpty() -> {
                    val key = arg(0)
                    if(key !in variables) warnings += "变量 $key 未设置，按空文字处理；检查前面的提示块是否启用"
                    variables[key].orEmpty()
                }
                command == "addvar" && args.size >= 2 -> {
                    val key=arg(0); val old=variables[key].orEmpty(); val extra=args.drop(1).joinToString("::").trim()
                    val a=old.toDoubleOrNull(); val b=extra.toDoubleOrNull()
                    variables[key] = if(a != null && b != null) number(a+b) else old+extra; ""
                }
                command in setOf("incvar", "decvar") && args.isNotEmpty() -> {
                    val key=arg(0); number((variables[key]?.toDoubleOrNull() ?: 0.0) + if(command=="incvar") 1 else -1).also { variables[key]=it }
                }
                command in setOf("random", "pick") && args.isNotEmpty() -> args[random.nextInt(args.size)].trim()
                command == "newline" -> "\n"
                command == "time" -> SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
                command == "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                args.isEmpty() && command in builtins -> if(command in setOf("description","personality","scenario","persona")) expandText(builtins.getValue(command), level + 1) else builtins.getValue(command)
                else -> {
                    warnings += "未支持的宏 ${first.take(60)} 保留原文；插件宏需改写后使用"
                    text.substring(start, end)
                }
            }
            out.append(replacement)
            require(out.length <= 1_000_000) { "宏展开内容过大，请简化预设" }
            cursor = end
        }
        return out.toString()
    }

    private fun number(value: Double): String = if(value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
