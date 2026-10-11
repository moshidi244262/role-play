package com.moyer.roleplay.core

import com.moyer.roleplay.data.*

data class PromptReport(val messages: List<PromptMessage>, val hits: List<String> = emptyList(),
    val warnings: List<String> = emptyList(), val trimmedMessages: Int = 0) {
    fun readable() = buildString {
        appendLine("世界书命中：${hits.size} 条；裁剪旧消息：$trimmedMessages 条")
        appendLine("估算上下文：${messages.sumOf { it.content.length }} 字符 / 约 ${messages.sumOf { estimateTokens(it.content) + 8 }} tokens（非精确计数）")
        if (hits.isNotEmpty()) appendLine(hits.joinToString("\n"))
        if (warnings.isNotEmpty()) appendLine("提示：\n" + warnings.distinct().joinToString("\n"))
        appendLine()
        messages.forEach { appendLine("[${it.role}]\n${it.content}\n") }
    }
}

fun estimateTokens(text: String): Int = kotlin.math.ceil(text.sumOf { if (it.code < 128) 0.25 else 1.5 }).toInt()

fun playerIdentity(session: ChatSession, config: RoleplayConfig = readConfig(session.config)): String =
    config.playerName.ifBlank { session.playerName.takeUnless { it.isBlank() || it == "玩家" } ?: "我" }

fun sessionProfile(profile: ConnectionProfile, config: RoleplayConfig): ConnectionProfile =
    profile.copy(model = config.modelOverride.ifBlank { profile.model })

object PromptBuilder {
    fun rules(config: RoleplayConfig, assets: List<ResourceAsset>): List<TextRule> = config.regexIds.mapNotNull { id ->
        assets.firstOrNull { it.id == id && it.enabled && it.kind == "regex" }
    }.flatMap { appJson.decodeFromString<RegexRules>(it.payload).rules }.filter { it.enabled }

    fun build(session: ChatSession, history: List<ChatMessage>): List<PromptMessage> = compile(session, history).messages

    fun compile(session: ChatSession, history: List<ChatMessage>, assets: List<ResourceAsset> = emptyList()): PromptReport {
        val card = appJson.decodeFromString<CharacterCard>(session.characterSnapshot)
        val config = readConfig(session.config)
        val user = playerIdentity(session, config)
        val rules = rules(config, assets)
        val warnings = mutableListOf<String>()
        if(rules.size>32) warnings+="已绑定超过 32 条启用正则，仅执行前 32 条"
        fun macros(text: String): String {
            val result = text.replace("{{char}}", card.name).replace("{{user}}", user)
                .replace("{{description}}", card.description).replace("{{personality}}", card.personality)
                .replace("{{scenario}}", card.scenario).replace("{{persona}}", config.persona)
            if (Regex("\\{\\{[^{}]+\\}\\}").containsMatchIn(result)) warnings += "部分高级宏未展开，请检查上下文或修改资料"
            return result
        }
        fun transform(text: String, target: String, depth: Int) = RegexEngine.apply(text,rules,target,false,depth,card.name,user)
            .also { warnings += it.warnings }.text
        val valid = history.filter { it.content.isNotBlank() && it.status != "failed" }
        val preset = listOfNotNull(config.characterPromptId,config.presetId).distinct().mapNotNull { id ->
            assets.firstOrNull { it.id == id && it.enabled && it.kind == "preset" }
        }.flatMap { appJson.decodeFromString<StylePreset>(it.payload).blocks.filter { block -> block.enabled && block.content.isNotBlank() } }
        val lore = selectLore(config, assets, valid, card.name, user) { text -> transform(macros(text), "lore", 0) }
        warnings += lore.warnings
        val view = when (config.narrativeView) {
            "first" -> "用户所扮演人物的自述、视角和状态栏使用第一人称‘我’；其他人物的称呼仍按人物关系处理。"
            "third" -> "叙述用户所扮演人物时采用第三人称，优先使用人物的实际姓名与设定称谓。人物直接发言中的第一人称仍为‘我’。"
            else -> "叙述视角遵循角色卡、示例对话和当前情境。用户所扮演人物以第一人称自述时称‘我’，不要机械地称自己为‘玩家’。"
        }
        val identity = "你正在进行虚构故事中的角色扮演。角色身份、性格和背景以角色卡为准。风格预设用于写法，不改变角色身份。世界书补充背景；若矛盾，优先保持角色卡中的人物事实。用户在故事中的身份称谓是 $user。‘玩家’是应用中的身份标签，不是默认人物姓名，也不是第一人称代词。$view 不要替用户决定行动或发言。"
        val core = buildString {
            appendLine("角色：${card.name}")
            if(card.description.isNotBlank()) appendLine("角色描述：${macros(card.description)}")
            if(card.personality.isNotBlank()) appendLine("性格：${macros(card.personality)}")
            if(card.scenario.isNotBlank()) appendLine("场景：${macros(card.scenario)}")
            if(config.persona.isNotBlank()) appendLine("玩家身份说明：${macros(config.persona)}")
            if(config.memory.isNotBlank()) appendLine("固定剧情记忆（可修订的剧情事实）：\n${macros(config.memory)}")
            if(card.exampleDialogue.isNotBlank()) appendLine("对话风格示例，不属于当前剧情：\n${macros(card.exampleDialogue)}")
        }
        data class Insertion(val role: String, val content: String, val position: String, val depth: Int)
        val additions = preset.map { Insertion(it.role, "叙事预设（${it.name}）：\n${macros(it.content)}",it.position,it.depth) } +
            lore.entries.sortedBy { it.entry.order }.map { Insertion(it.entry.role,"世界书背景：\n${it.content}",it.entry.position,it.entry.depth) } +
            listOfNotNull(config.authorNote.takeIf { it.isNotBlank() }?.let { Insertion("system","作者注释：\n${macros(it)}","depth",config.authorDepth) })
        val fixedLength = identity.length + core.length + additions.sumOf { it.content.length }
        require(fixedLength < config.historyBudgetChars) { "角色与固定资料超过上下文字符预算，请减少资料或在会话设定中提高预算" }
        val recent = valid.takeLast(200)
        var available = config.historyBudgetChars - fixedLength
        val dialogue = mutableListOf<PromptMessage>()
        for (index in recent.indices.reversed()) {
            val message = recent[index]
            val content = transform(message.content, message.role, recent.lastIndex-index)
            if(content.length > available) {
                require(dialogue.isNotEmpty()) { "最新消息超过上下文字符预算，请缩短消息或提高会话预算" }
                break
            }
            dialogue.add(0,PromptMessage(message.role,content));available-=content.length
        }
        val depthInsertions = additions.filter { it.position == "depth" }.groupBy { (dialogue.size - it.depth).coerceAtLeast(0) }
        val withDepth = buildList {
            for(i in 0..dialogue.size) {
                depthInsertions[i]?.forEach { add(PromptMessage(it.role,it.content)) }
                if(i < dialogue.size) add(dialogue[i])
            }
        }
        val result = buildList {
            add(PromptMessage("system", identity))
            additions.filter { it.position == "before_char" }.forEach { add(PromptMessage(it.role,it.content)) }
            add(PromptMessage("system",core))
            additions.filter { it.position == "after_char" }.forEach { add(PromptMessage(it.role,it.content)) }
            addAll(withDepth)
        }
        if(config.references().any { id -> assets.none { it.id == id } }) warnings += "有绑定资料未找到，已跳过；可重新选择资料"
        return PromptReport(result,lore.entries.map { "${it.bookName} / ${it.entry.name}（${it.reason}）" },warnings.distinct(),valid.size-dialogue.size)
    }

    private data class Hit(val bookId: String,val bookName: String,val entry: LoreEntry,val content: String,val reason: String)
    private data class LoreResult(val entries: List<Hit>,val warnings: List<String>)
    private fun selectLore(config: RoleplayConfig,assets: List<ResourceAsset>,history: List<ChatMessage>,charName: String,userName: String,
        transform: (String)->String): LoreResult {
        val books = config.lorebookIds.mapNotNull { id -> assets.firstOrNull { it.id==id && it.kind=="lorebook" && it.enabled } }
            .map { it to appJson.decodeFromString<LoreBook>(it.payload) }
        val hits = mutableListOf<Hit>();val warnings = mutableListOf<String>();val seen=mutableSetOf<String>()
        var total=0; val perBook=mutableMapOf<String,Int>();val deadline=System.nanoTime()+1_000_000_000
        fun matches(key: String,text: String,sensitive: Boolean): Boolean {
            val expanded=key.replace("{{char}}",charName).replace("{{user}}",userName)
            return if(expanded.startsWith('/')) RegexEngine.matches(expanded,text) else expanded.isNotBlank() && text.contains(expanded,!sensitive)
        }
        for (pass in 0..3) {
            val candidates = mutableListOf<Hit>()
            for((asset,book) in books) for(entry in book.entries) {
                if (System.nanoTime()>deadline) {warnings+="世界书扫描达到时间预算，剩余条目未扫描";return LoreResult(hits,warnings)}
                if(!entry.enabled || "${asset.id}:${entry.id}" in seen || (pass>0 && (!book.recursive || entry.excludeRecursion))) continue
                val depth=entry.scanDepth ?: config.scanDepth.takeUnless { it==4 } ?: book.scanDepth
                val scan=history.takeLast(depth).joinToString("\n") { "${if(it.role=="user") userName else charName}: ${it.content}" } +
                    if(pass>0) hits.filter { !it.entry.preventRecursion }.joinToString("\n") { it.content } else ""
                val primary=entry.constant || entry.keys.any { matches(it,scan,entry.caseSensitive) }
                val secondary=entry.secondaryKeys.map { matches(it,scan,entry.caseSensitive) }
                val filter=entry.constant || !entry.selective || secondary.isEmpty() || when(entry.logic) {
                    "and_all"->secondary.all{it};"not_any"->secondary.none{it};"not_all"->!secondary.all{it};else->secondary.any{it}
                }
                if(primary && filter && entry.content.isNotBlank()) candidates += Hit(asset.id,asset.name,entry,transform(entry.content),
                    if(entry.constant) "常驻" else if(pass==0) "关键词" else "递归")
            }
            var added=0
            candidates.sortedWith(compareByDescending<Hit>{it.entry.constant}.thenByDescending{it.entry.order}).forEach { hit ->
                val count=estimateTokens(hit.content)
                val bookLimit=books.first { it.first.id==hit.bookId }.second.budget
                seen+="${hit.bookId}:${hit.entry.id}"
                if(total+count > config.loreBudget || (perBook[hit.bookId]?:0)+count>bookLimit) warnings += "${hit.bookName} / ${hit.entry.name}：世界书预算不足，未插入"
                else {hits+=hit;total+=count;perBook[hit.bookId]=(perBook[hit.bookId]?:0)+count;added++}
            }
            if(added==0) break
        }
        return LoreResult(hits,warnings)
    }
}
