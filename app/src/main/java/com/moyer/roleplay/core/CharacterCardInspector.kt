package com.moyer.roleplay.core

import com.moyer.roleplay.data.CharacterCard
import kotlinx.serialization.json.*
import java.nio.ByteBuffer

data class CardTextSection(val title: String, val body: String)
data class CardInspection(val name: String, val summary: String, val sections: List<CardTextSection>, val sourceJson: String) {
    fun plainText(): String = buildString {
        appendLine("角色卡明文：$name")
        appendLine(summary)
        sections.forEach { appendLine("\n【${it.title}】"); appendLine(it.body) }
    }
}

/** Reads metadata only. Card scripts, HTML, regexes and macros are never executed. */
object CharacterCardInspector {
    fun sourceText(bytes: ByteArray): String {
        require(bytes.size <= BackupCodec.MAX_BYTES) { "文件超过 16 MB，暂不支持" }
        if (PngCardCodec.isPng(bytes)) return PngCardCodec.extract(bytes)
        val jpeg = bytes.size >= 2 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte()
        require(!jpeg) { "收到的是 JPEG 图片，没有可读取的 PNG 角色卡资料；即使文件名是 .png 也无法还原。请从软件中读取已导入的角色，或选择原始 PNG / JSON 文件。" }
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("文件不是 UTF-8 JSON 或 PNG 角色卡；请使用原始文件，普通图片无法还原角色资料") }
    }

    fun inspect(bytes: ByteArray): CardInspection {
        val text=sourceText(bytes)
        val root = try { appJson.parseToJsonElement(text) }
        catch (_: Exception) { throw IllegalArgumentException("文件不是有效的 JSON 或 PNG 角色卡") }
        require(root is JsonObject) { "请选择角色卡文件，不能解析预设、世界书或备份" }
        return inspectRoot(root)
    }

    fun stored(card: CharacterCard): CardInspection {
        if (card.sourceJson.isNotBlank()) {
            val root = appJson.parseToJsonElement(card.sourceJson)
            require(root is JsonObject) { "保存的原始角色资料无效" }
            return inspectRoot(root)
        }
        val root = buildJsonObject {
            put("name",card.name); put("description",card.description); put("personality",card.personality)
            put("scenario",card.scenario); put("first_mes",card.greeting); put("mes_example",card.exampleDialogue)
            put("alternate_greetings",appJson.parseToJsonElement(card.alternateGreetings))
            put("app_session_config",appJson.parseToJsonElement(card.config))
        }
        return inspectRoot(root).let { it.copy(summary=it.summary+"\n此角色没有保存原始卡片，仅展示软件中的现有设定。") }
    }

    private fun inspectRoot(original: JsonObject): CardInspection {
        val root = CommunityImporter.scrub(original) as JsonObject
        val spec = (root["spec"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val data = if (spec in setOf("chara_card_v2","chara_card_v3")) root["data"] as? JsonObject
            ?: throw IllegalArgumentException("角色卡缺少 data 字段") else root
        val name = (data["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(name.isNotBlank() && "format" !in root && "entries" !in data && "prompts" !in data &&
            (spec in setOf("chara_card_v2","chara_card_v3") || listOf("description","personality","scenario","first_mes","greeting","mes_example","exampleDialogue").any { it in data })) {
            "请选择 PNG 或 JSON 角色卡；预设、世界书和备份请使用各自入口"
        }
        val sections = mutableListOf<CardTextSection>()
        root.filterKeys { it != "data" && data !== root }.takeIf { it.isNotEmpty() }?.let {
            sections += CardTextSection("卡片格式与其他字段", readable(JsonObject(it)))
        }
        data.filterKeys {it != "character_book"}.forEach { (key,value) ->
            sections += CardTextSection(label(key),readable(value))
        }
        val book = data["character_book"] as? JsonObject
        val entries = when(val value = book?.get("entries")) {
            is JsonArray -> value.toList()
            is JsonObject -> value.values.toList()
            else -> emptyList()
        }
        if(book != null) {
            sections += CardTextSection("世界书设置", readable(JsonObject(book.filterKeys { it != "entries" })))
            entries.forEachIndexed { index,value ->
                val entry = value as? JsonObject
                val title = listOf("comment","name").firstNotNullOfOrNull { key ->
                    (entry?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                } ?: "条目 ${index+1}"
                sections += CardTextSection("世界书 ${index+1}：$title",readable(value))
            }
        }
        val constant = entries.filterIsInstance<JsonObject>().filter {
            (it["constant"] as? JsonPrimitive)?.booleanOrNull == true &&
                (it["enabled"] as? JsonPrimitive)?.booleanOrNull != false && (it["disable"] as? JsonPrimitive)?.booleanOrNull != true
        }
        val cost = constant.sumOf { estimateTokens((it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
        val budget = (book?.get("token_budget") as? JsonPrimitive)?.intOrNull
        val summary = buildString {
            append("格式：${spec.ifBlank { "旧版 / 普通 JSON" }}；内嵌世界书 ${entries.size} 条")
            if(book != null) {
                append("\n原文件本书预算：${budget?.toString() ?: "未指定，导入后跟随会话预算"}")
                append("\n启用常驻条目 ${constant.size} 条，原文合计约 $cost tokens（估算，宏展开后可能变化）")
            }
            append("\n以下是卡片保存的原始内容，包含未启用条目和扩展；资料库中的后续修改请在资料库查看。")
            append("\n仅解析文字，不执行卡片脚本；密钥和密码字段已移除。")
        }
        val compatibility=runCatching {CommunityImporter.decode(root.toString().toByteArray(Charsets.UTF_8),"$name.json")}
        val report=compatibility.fold(onSuccess={pack -> buildString {
            appendLine("已转换：角色常用字段、开场白、示例对话、备选开场白")
            appendLine("关联资料：${pack.resources.count {it.kind=="lorebook"}}本世界书、${pack.resources.count {it.kind=="preset"}}份角色提示、${pack.resources.count {it.kind=="regex"}}份正则")
            appendLine("聊天显示：支持文本、加粗、斜体、基础表格和折叠状态栏；原始CSS布局不会完整复现")
            appendLine("扩展执行：酒馆助手、MVU、STscript和任意JavaScript保留原文，当前不执行")
            if(pack.warnings.isNotEmpty()) {appendLine("该卡片的转换提示：");pack.warnings.forEach {appendLine("• $it")}}
            appendLine("解析成功不代表所有扩展已经生效；实际发送内容请在聊天上下文中查看。")
        }},onFailure={"原文读取成功，但兼容转换未通过：${(it as? IllegalArgumentException)?.message ?: "资料格式需要检查"}。请先修正文件后再导入。"})
        sections.add(0,CardTextSection("兼容性报告",report))
        val pretty = Json { prettyPrint=true }.encodeToString(JsonElement.serializer(),root)
        return CardInspection(name,summary,sections,pretty)
    }

    private fun readable(value: JsonElement,depth: Int=0): String = when(value) {
        is JsonPrimitive -> if(value is JsonNull) "未指定" else value.content
        is JsonArray -> if(value.isEmpty()) "（空列表）" else value.mapIndexed {index,item -> "${index+1}. ${readable(item,depth+1)}"}.joinToString("\n")
        is JsonObject -> if(value.isEmpty()) "（无）" else if(depth >= 24) value.toString() else value.entries.joinToString("\n\n") { (key,item) ->
            "${label(key)}：\n${readable(item,depth+1)}"
        }
    }

    private fun label(key: String): String = when(key) {
        "name"->"名称"; "description"->"角色描述"; "personality"->"性格"; "scenario"->"场景"
        "first_mes","greeting"->"开场白"; "mes_example","exampleDialogue"->"对话示例"; "alternate_greetings"->"备选开场白"
        "creator_notes"->"作者说明"; "system_prompt"->"角色系统提示"; "post_history_instructions"->"角色后置提示"
        "creator"->"作者"; "character_version"->"角色版本"; "tags"->"标签"; "extensions"->"扩展字段（原文）"
        "content"->"内容"; "keys","key"->"触发关键词"; "secondary_keys","keysecondary"->"辅助关键词"
        "enabled"->"启用"; "disable"->"停用"; "constant"->"常驻"; "comment"->"条目名称"
        "token_budget"->"本书预算"; "scan_depth"->"扫描深度"; "recursive_scanning"->"递归扫描"
        else->key
    }
}
