package com.moyer.roleplay.core

import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class ImportPreview(val backup: BackupBundle? = null, val characters: List<CharacterCard> = emptyList(),
    val resources: List<ResourceAsset> = emptyList(), val warnings: List<String> = emptyList()) {
    val characterCount get() = backup?.characters?.size ?: characters.size
    val sessionCount get() = backup?.sessions?.size ?: 0
    val messageCount get() = backup?.messages?.size ?: 0
    val resourceCount get() = backup?.resources?.size ?: resources.size
}

private fun JsonObject.string(key: String, fallback: String = "") = (this[key] as? JsonPrimitive)?.contentOrNull ?: fallback
private fun JsonObject.int(key: String, fallback: Int = 0) = (this[key] as? JsonPrimitive)?.intOrNull ?: fallback
private fun JsonObject.bool(key: String, fallback: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
private fun JsonObject.strings(key: String): List<String> = when (val value = this[key]) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
    is JsonPrimitive -> value.contentOrNull?.split(',', '，', '\n')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    else -> emptyList()
}

object CommunityImporter {
    private val secretFields = setOf("apikey", "openaiapikey", "claudeapikey", "deepseekapikey", "authorization",
        "cookie", "cookies", "password", "proxypassword", "accesstoken", "refreshtoken", "secretkey")
    fun scrub(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it.lowercase().replace("_", "").replace("-", "") !in secretFields }
            .mapValues { scrub(it.value) })
        is JsonArray -> JsonArray(value.map { scrub(it) })
        else -> value
    }
    fun decode(bytes: ByteArray, filename: String = "导入资料", expectedKind: String? = null): ImportPreview {
        val result = decodeAny(bytes, filename)
        if (expectedKind != null) {
            require(expectedKind in setOf("preset", "lorebook")) { "导入类型无效" }
            require(result.backup == null && result.characters.isEmpty() && result.resources.any { it.kind == expectedKind }) {
                "请选择${if(expectedKind == "preset") "预设" else "世界书"} JSON 文件；其他资料可使用通用导入入口"
            }
        }
        return result
    }
    private fun decodeAny(bytes: ByteArray, filename: String): ImportPreview {
        require(bytes.size <= BackupCodec.MAX_BYTES) { "文件超过 16 MB，暂不支持" }
        val text = if (PngCardCodec.isPng(bytes)) PngCardCodec.extract(bytes) else bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val root = try { appJson.parseToJsonElement(text) } catch (_: Exception) { throw IllegalArgumentException("文件不是有效的 JSON 或 PNG 角色卡") }
        if (root is JsonObject && root.string("format") in setOf("roleplay.backup", "roleplay.chat"))
            return ImportPreview(backup = BackupCodec.decode(text))
        val clean = scrub(root)
        val name = filename.substringBeforeLast('.').take(160).ifBlank { "导入资料" }
        if (clean is JsonArray && clean.all { it is JsonObject && "findRegex" in it }) {
            val asset = regex(clean, name)
            return ImportPreview(resources = listOf(asset), warnings = asset.notes.lines().filter { it.isNotBlank() })
        }
        require(clean is JsonObject) { "未识别的资料格式" }
        if (clean.string("format") == "roleplay.asset") {
            val asset = try { appJson.decodeFromJsonElement<ResourceAsset>(clean.getValue("asset")) }
            catch (_: Exception) { throw IllegalArgumentException("本 App 资料文件无效") }
            asset.validate()
            return ImportPreview(resources = listOf(asset.copy(id = newId(), sourceJson = scrub(appJson.parseToJsonElement(asset.sourceJson.ifBlank { "{}" })).toString())))
        }
        return when {
            "entries" in clean -> lore(clean, clean.string("name", name)).let { ImportPreview(resources = listOf(it), warnings = it.notes.lines().filter(String::isNotBlank)) }
            ("findRegex" in clean || "regex_scripts" in clean) && "prompts" !in clean -> regex(clean["regex_scripts"] ?: clean, clean.string("scriptName", name)).let {
                ImportPreview(resources = listOf(it), warnings = it.notes.lines().filter(String::isNotBlank)) }
            "prompts" in clean || ("system_prompt" in clean && "name" !in clean) -> preset(clean, name).let {
                ImportPreview(resources = listOf(it), warnings = it.notes.lines().filter(String::isNotBlank)) }
            clean.string("spec") in setOf("chara_card_v2", "chara_card_v3") || "name" in clean -> character(clean)
            else -> throw IllegalArgumentException("未识别的资料：请选择角色卡、世界书、提示词预设、正则或本 App 的备份文件")
        }
    }

    private fun character(root: JsonObject): ImportPreview {
        val data = if (root.string("spec").startsWith("chara_card_")) root["data"] as? JsonObject
            ?: throw IllegalArgumentException("角色卡缺少 data 字段") else root
        val name = data.string("name").trim()
        require(name.isNotBlank() && name.length <= 200) { "角色卡缺少有效名称" }
        val resources = mutableListOf<ResourceAsset>(); val warnings = mutableListOf<String>()
        val book = (data["character_book"] as? JsonObject)?.let { lore(it, "$name · 世界书") }
        book?.let { resources += it }
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val regex = (extensions["regex_scripts"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { regex(it, "$name · 正则") }
        regex?.let { resources += it }
        val blocks = mutableListOf<PromptBlock>()
        data.string("system_prompt").takeIf { it.isNotBlank() }?.let { blocks += PromptBlock(name = "角色附加提示", content = it) }
        data.string("post_history_instructions").takeIf { it.isNotBlank() }?.let { blocks += PromptBlock(name = "角色后置提示", content = it, position = "depth", depth = 0) }
        (extensions["depth_prompt"] as? JsonObject)?.let { depth ->
            depth.string("prompt").takeIf(String::isNotBlank)?.let { blocks += PromptBlock(name = "角色深度提示", content = it,
                position = "depth", depth = depth.int("depth", 4).coerceIn(0,100), role = depth.string("role", "system").takeIf { it in setOf("system","user","assistant") } ?: "system") }
        }
        val preset = blocks.takeIf { it.isNotEmpty() }?.let { ResourceAsset(kind = "preset", name = "$name · 提示词",
            payload = appJson.encodeToString(StylePreset(it))) }
        preset?.let { resources += it }
        if (data["assets"] is JsonArray || data["group_only_greetings"] is JsonArray) warnings += "CCv3 外部资源及群聊开场白已保留在原始资料中，当前不加载或执行"
        warnings += resources.flatMap { it.notes.lines().filter(String::isNotBlank) }
        val config = RoleplayConfig(lorebookIds = listOfNotNull(book?.id), regexIds = listOfNotNull(regex?.id), characterPromptId = preset?.id)
        val card = CharacterCard(name = name, description = data.string("description"), personality = data.string("personality"),
            scenario = data.string("scenario"), greeting = data.string("first_mes", data.string("greeting")),
            exampleDialogue = data.string("mes_example", data.string("exampleDialogue")), config = config.json(),
            sourceJson = root.toString(), alternateGreetings = appJson.encodeToString(data.strings("alternate_greetings").also {
                require(it.size<=100 && it.all {greeting->greeting.length<=32000}) {"备选开场白超出限制"}
            }))
        return ImportPreview(characters = listOf(card), resources = resources, warnings = warnings.distinct())
    }

    fun lore(root: JsonObject, name: String): ResourceAsset {
        val notes = mutableListOf<String>()
        val values = when (val entries = root["entries"]) {
            is JsonObject -> entries.values.toList()
            is JsonArray -> entries.toList()
            else -> throw IllegalArgumentException("世界书缺少 entries")
        }
        require(values.size <= 5000) { "世界书超过 5000 个条目" }
        val entries = values.mapIndexed { index, value ->
            val e = value as? JsonObject ?: throw IllegalArgumentException("世界书条目格式无效")
            val ext = e["extensions"] as? JsonObject ?: JsonObject(emptyMap())
            val title = e.string("comment", e.string("name", "条目 ${index+1}"))
            val p = e["position"] ?: ext["position"]
            val number = (p as? JsonPrimitive)?.intOrNull
            val position = when (number) { 0 -> "before_char"; 1 -> "after_char"; 2 -> "before_author"; 3 -> "after_author"; 4 -> "depth"; 5 -> "before_examples"; 6 -> "after_examples"; else ->
                (p as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("before_char","after_char","depth","before_examples","after_examples","before_author","after_author") } ?: if (p == null) "after_char" else "unsupported" }
            val advanced = listOf("sticky","cooldown","delay").any { e.int(it, ext.int(it)) != 0 } ||
                e.string("group", ext.string("group")).isNotBlank() || e.bool("vectorized", ext.bool("vectorized")) ||
                e.bool("delayUntilRecursion", ext.bool("delayUntilRecursion")) || e.int("delayUntilRecursion", ext.int("delayUntilRecursion")) != 0 ||
                ((e["triggers"] ?: ext["triggers"]) as? JsonArray)?.isNotEmpty() == true
            val primary=e.strings(if ("key" in e) "key" else "keys")
            val secondary=e.strings(if ("keysecondary" in e) "keysecondary" else "secondary_keys")
            val badRegex=(primary+secondary).any {it.startsWith('/') && runCatching {RegexEngine.compile(it.replace("{{char}}","角色").replace("{{user}}","玩家"))}.isFailure}
            val enabled = !e.bool("disable") && e.bool("enabled", true) && position != "unsupported" && !advanced && !badRegex
            if (position == "unsupported" || advanced) notes += "$title：高级位置、分组、向量、生成类型或定时触发未支持，条目暂不启用；原始字段已保留"
            if(badRegex) notes+="$title：关键词正则包含不支持的语法，条目暂不启用"
            LoreEntry(name = title, keys = primary, secondaryKeys = secondary,
                selective = e.bool("selective"), logic = when(e.int("selectiveLogic", ext.int("selectiveLogic"))) {
                    1 -> "not_all"; 2 -> "not_any"; 3 -> "and_all"; else -> "and_any" },
                constant = e.bool("constant"), enabled = enabled, content = e.string("content"),
                order = e.int("order", e.int("insertion_order", 100)), position = if(position == "unsupported") "after_char" else position,
                depth = e.int("depth", ext.int("depth", 4)).coerceIn(0,100),
                role = when (e.int("role", ext.int("role"))) { 1 -> "user"; 2 -> "assistant"; else -> "system" },
                caseSensitive = e.bool("case_sensitive", e.bool("caseSensitive", ext.bool("caseSensitive"))),
                scanDepth = (e["scanDepth"] as? JsonPrimitive)?.intOrNull?.coerceIn(0,100),
                preventRecursion = e.bool("preventRecursion", ext.bool("preventRecursion")),
                excludeRecursion = e.bool("excludeRecursion", ext.bool("excludeRecursion")),
                matchWholeWords = e.bool("matchWholeWords", ext.bool("matchWholeWords")),
                probability = if(e.bool("useProbability", ext.bool("useProbability", true))) e.int("probability", ext.int("probability",100)).coerceIn(0,100) else 100,
                matchPersona = e.bool("matchPersonaDescription", ext.bool("matchPersonaDescription")),
                matchDescription = e.bool("matchCharacterDescription", ext.bool("matchCharacterDescription")),
                matchPersonality = e.bool("matchCharacterPersonality", ext.bool("matchCharacterPersonality")),
                matchScenario = e.bool("matchScenario", ext.bool("matchScenario")))
        }
        val book = LoreBook(entries, root.bool("recursive_scanning", root.bool("recursive")),
            root.int("scan_depth", 4).coerceIn(0,100), root.int("token_budget", 2048).coerceIn(0,16384))
        return ResourceAsset(kind = "lorebook", name = name.take(200), payload = appJson.encodeToString(book),
            sourceJson = root.toString(), notes = notes.distinct().take(100).joinToString("\n")).also { it.validate() }
    }

    fun preset(root: JsonObject, name: String): ResourceAsset {
        val notes = mutableListOf<String>()
        val prompts = root["prompts"] as? JsonArray
        require(prompts == null || prompts.size <= 1000) { "预设超过 1000 个提示块" }
        val orders = (root["prompt_order"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val orderList = (orders.firstOrNull { it.int("character_id") == 100001 } ?: orders.lastOrNull())?.get("order") as? JsonArray
        if(orders.size > 1) notes += "文件含多个排列，采用全局排列（100001 优先）；未逐角色应用其他排列"
        val order = orderList?.mapNotNull { it as? JsonObject }.orEmpty()
        val rank = order.mapIndexed { i,e -> e.string("identifier") to i }.toMap()
        val enabled = order.associate { it.string("identifier") to it.bool("enabled", true) }
        val markers = setOf("worldInfoBefore", "worldInfoAfter", "charDescription", "charPersonality", "scenario", "personaDescription", "dialogueExamples", "chatHistory")
        val blocks = if (prompts != null) prompts.mapIndexedNotNull { index,value ->
            val p = value as? JsonObject ?: throw IllegalArgumentException("提示块格式无效")
            val id = p.string("identifier", index.toString())
            val marker = if(p.bool("marker")) id else ""
            if(marker.isNotBlank() && marker !in markers) {
                notes += "${p.string("name",id)}：未知占位块未加载，原始字段已保留"
                return@mapIndexedNotNull null
            }
            val role = p.string("role", "system")
            val trigger = p["injection_trigger"]
            val triggers = if(trigger is JsonArray) trigger.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } else emptyList()
            val supported = role in setOf("system","user","assistant") && triggers.all { it in setOf("normal","continue","regenerate","swipe") } && !(trigger is JsonPrimitive && trigger.booleanOrNull == true)
            if (!supported) notes += "${p.string("name", id)}：提示块触发方式或角色不兼容，暂不启用"
            (rank[id] ?: (order.size + index)) to PromptBlock(name = p.string("name", id), content = p.string("content"),
                enabled = supported && (if(orderList != null) enabled[id] == true else p.bool("enabled", true)),
                role = role.takeIf { it in setOf("system","user","assistant") } ?: "system",
                position = if(p.int("injection_position") == 1) "depth" else "after_char",
                depth = p.int("injection_depth",0).coerceIn(0,100), marker = marker,
                order = p.int("injection_order",100), triggers = if(supported) triggers else emptyList())
        }.sortedBy { it.first }.map { it.second } else listOfNotNull(
            root.string("system_prompt", root.string("content")).takeIf(String::isNotBlank)?.let { PromptBlock(name="主提示", content=it) },
            root.string("post_history_instructions").takeIf(String::isNotBlank)?.let { PromptBlock(name="后置提示", content=it,position="depth") }
        )
        require(blocks.any { it.marker.isNotBlank() || it.content.isNotBlank() }) { "预设没有可用的文字提示块或占位块" }
        val ext = root["extensions"] as? JsonObject
        val scripts = (ext?.get("regex_scripts") ?: root["regex_scripts"]) as? JsonArray
        val rules = scripts?.takeIf { it.isNotEmpty() }?.let {
            val asset = regex(it, "$name · 内嵌正则")
            notes += asset.notes.lines().filter(String::isNotBlank)
            appJson.decodeFromString<RegexRules>(asset.payload).rules
        }.orEmpty()
        if(rules.isNotEmpty()) notes += "内嵌正则 ${rules.size} 条随此预设启用；可在预设编辑页逐条调整，无需重复绑定"
        if(ext?.keys?.any { it != "regex_scripts" } == true) notes += "扩展字段已保留；SPreset、酒馆助手、MVU、JavaScript 与 STscript 不执行。依赖它们的动态变量或界面可能不完整"
        if(listOf("temperature","top_p","openai_max_tokens","custom_url","reverse_proxy").any { it in root })
            notes += "服务地址、密钥和采样参数不会随预设生效；输出上限使用 API 连接的配置"
        notes += "支持顺序、占位块、普通/续写/重生成触发与常用宏。setvar/getvar 变量仅用于本次组装，不是跨消息的 MVU 状态"
        val preset = StylePreset(blocks, ordered=prompts != null, regexRules=rules,
            newChatPrompt=root.string("new_chat_prompt"), newExamplePrompt=root.string("new_example_chat_prompt"),
            worldInfoFormat=root.string("wi_format","{0}"), scenarioFormat=root.string("scenario_format","{{scenario}}"),
            personalityFormat=root.string("personality_format","{{personality}}"))
        return ResourceAsset(kind="preset", name=name.take(200), payload=appJson.encodeToString(preset),
            sourceJson=root.toString(),notes=notes.distinct().take(100).joinToString("\n")).also { it.validate() }
    }

    fun regex(root: JsonElement, name: String): ResourceAsset {
        val notes = mutableListOf<String>()
        val list = if (root is JsonArray) root else JsonArray(listOf(root))
        require(list.size <= 256) { "一个规则文件最多包含 256 条正则，请拆分导入" }
        val rules = list.mapIndexed { i,value ->
            val e = value as? JsonObject ?: throw IllegalArgumentException("正则条目格式无效")
            val pattern = e.string("findRegex"); val title = e.string("scriptName", "规则 ${i+1}")
            val placement = (e["placement"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: listOf(2)
            val substitution=e.int("substituteRegex").coerceIn(0,2)
            val supported = runCatching { RegexEngine.compileRule(TextRule(pattern=pattern,substituteFind=substitution)) }.isSuccess
            if (!supported) notes += "$title：表达式或标志不兼容，暂不启用；可在测试预览中修改"
            if (placement.any { it !in setOf(1,2,5) }) notes += "$title：命令和推理内容作用域不执行"
            val targets = placement.mapNotNull { when(it) { 1->"user";2->"assistant";5->"lore";else->null } }
            val md = e.bool("markdownOnly"); val prompt = e.bool("promptOnly")
            TextRule(name=title,pattern=pattern,replacement=e.string("replaceString"),enabled=!e.bool("disabled") && supported && targets.isNotEmpty(),
                targets=targets,display=md || !prompt,prompt=prompt || !md,
                minDepth=e.int("minDepth").coerceAtLeast(0),maxDepth=e.int("maxDepth",-1),
                substituteFind=substitution,trims=e.strings("trimStrings"))
        }
        if(rules.count { it.enabled } > 32) notes += "启用规则超过 32 条，每次只执行绑定顺序中的前 32 条；请停用不需要的规则"
        notes += "正则保留消息原文，只改变显示或发送给模型的文字；不执行 JavaScript、HTML 或斜杠命令"
        return ResourceAsset(kind="regex",name=name.take(200),payload=appJson.encodeToString(RegexRules(rules)),
            sourceJson=root.toString(),notes=notes.distinct().joinToString("\n")).also { it.validate() }
    }
}
