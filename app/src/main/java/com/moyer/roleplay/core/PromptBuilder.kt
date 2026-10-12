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
    private fun selectedPreset(config: RoleplayConfig, assets: List<ResourceAsset>): StylePreset? =
        assets.firstOrNull { it.id == config.presetId && it.enabled && it.kind == "preset" }?.let { appJson.decodeFromString<StylePreset>(it.payload) }

    fun rules(config: RoleplayConfig, assets: List<ResourceAsset>): List<TextRule> {
        val standalone = config.regexIds.mapNotNull { id -> assets.firstOrNull { it.id == id && it.enabled && it.kind == "regex" } }
            .flatMap { appJson.decodeFromString<RegexRules>(it.payload).rules }
        return (standalone + selectedPreset(config, assets)?.regexRules.orEmpty()).filter { it.enabled }
    }

    fun build(session: ChatSession, history: List<ChatMessage>): List<PromptMessage> = compile(session, history).messages

    fun compile(session: ChatSession, history: List<ChatMessage>, assets: List<ResourceAsset> = emptyList(), generationType: String = "normal"): PromptReport {
        val card = appJson.decodeFromString<CharacterCard>(session.characterSnapshot)
        val config = readConfig(session.config)
        val user = playerIdentity(session, config)
        val rules = rules(config, assets)
        val warnings = mutableListOf<String>()
        if(rules.size > 32) warnings += "已绑定超过 32 条启用正则，仅执行前 32 条"
        val valid = history.filter { it.content.isNotBlank() && it.status != "failed" }
        val expander = MacroExpander(mapOf("char" to card.name, "user" to user, "description" to card.description,
            "personality" to card.personality, "scenario" to card.scenario, "persona" to config.persona,
            "lastUserMessage" to valid.lastOrNull { it.role == "user" }?.content.orEmpty(),
            "lastCharMessage" to valid.lastOrNull { it.role == "assistant" }?.content.orEmpty(),
            "lastMessage" to valid.lastOrNull()?.content.orEmpty(), "original" to ""),
            (session.id + valid.joinToString { it.role + it.content }).hashCode())
        fun basic(text: String) = text.replace("{{char}}",card.name).replace("{{user}}",user)
        fun transform(text: String, target: String, depth: Int) = RegexEngine.apply(text,rules,target,false,depth,card.name,user)
            .also { warnings += it.warnings }.text
        val style = selectedPreset(config, assets)
        val eligible: (PromptBlock) -> Boolean = { it.enabled && (it.triggers.isEmpty() || generationType in it.triggers || (generationType=="regenerate" && "swipe" in it.triggers)) }
        val characterBlocks = assets.firstOrNull { it.id == config.characterPromptId && it.enabled && it.kind=="preset" }
            ?.let { appJson.decodeFromString<StylePreset>(it.payload).blocks.filter(eligible) }.orEmpty()
        val styleBlocks = style?.blocks?.filter(eligible).orEmpty()
        val lore = selectLore(config, assets, valid, card, user) { text -> transform(basic(text), "lore", 0) }
        warnings += lore.warnings
        val view = when (config.narrativeView) {
            "first" -> "用户所扮演人物的自述、视角和状态栏使用第一人称‘我’；其他人物的称呼仍按人物关系处理。"
            "third" -> "叙述用户所扮演人物时采用第三人称，优先使用人物的实际姓名与设定称谓。人物直接发言中的第一人称仍为‘我’。"
            else -> "叙述视角遵循角色卡、示例对话和当前情境。用户所扮演人物以第一人称自述时称‘我’，不要机械地称自己为‘玩家’。"
        }
        val identity = "你正在进行虚构故事中的角色扮演。角色身份、性格和背景以角色卡为准。风格预设用于写法，不改变角色身份。世界书补充背景；若矛盾，优先保持角色卡中的人物事实。用户在故事中的身份称谓是 $user。‘玩家’是应用中的身份标签，不是默认人物姓名，也不是第一人称代词。$view 不要替用户决定行动或发言。"
        data class Segment(val role: String="system", val raw: String="", val history: Boolean=false, val examples: Boolean=false)
        data class Insertion(val role: String, val raw: String, val depth: Int, val order: Int=100)
        val segments=mutableListOf(Segment(raw=identity))
        if(config.memory.isNotBlank()) segments += Segment(raw="固定剧情记忆（可修订的剧情事实）：\n${config.memory}")
        val sortedLore=lore.entries.sortedBy { it.entry.order }
        fun loreText(position: String) = sortedLore.filter { it.entry.position==position }.joinToString("\n") { it.content }
        fun loreSegments(position: String) = sortedLore.filter { it.entry.position==position }.map { Segment(it.entry.role,"世界书背景：\n${it.content}") }
        val depth=mutableListOf<Insertion>()
        (characterBlocks + styleBlocks).filter { it.position=="depth" && it.marker.isBlank() }.forEach { depth += Insertion(it.role,it.content,it.depth,it.order) }
        sortedLore.filter { it.entry.position=="depth" }.forEach { depth += Insertion(it.entry.role,"世界书背景：\n${it.content}",it.entry.depth,it.entry.order) }
        val note=buildString {
            append(loreText("before_author"))
            if(config.authorNote.isNotBlank()) append("\n作者注释：\n${config.authorNote}")
            append("\n"+loreText("after_author"))
        }.trim()
        if(note.isNotBlank()) depth += Insertion("system",note,config.authorDepth)
        segments += characterBlocks.filter { it.position=="before_char" && it.content.isNotBlank() }.map { Segment(it.role,it.content) }
        if(style?.ordered == true) {
            var historyAdded=false
            for(block in styleBlocks.filter { it.position != "depth" || it.marker.isNotBlank() }) {
                when(block.marker) {
                    "chatHistory" -> if(!historyAdded) { segments += Segment(history=true); historyAdded=true }
                        else warnings += "重复聊天历史占位块已跳过"
                    "dialogueExamples" -> { segments += loreSegments("before_examples"); segments += Segment(raw=card.exampleDialogue,examples=true); segments += loreSegments("after_examples") }
                    "charDescription" -> segments += Segment(raw="角色：${card.name}\n角色描述：${card.description}")
                    "charPersonality" -> if(card.personality.isNotBlank()) segments += Segment(raw=style.personalityFormat.ifBlank { "{{personality}}" })
                    "scenario" -> if(card.scenario.isNotBlank()) segments += Segment(raw=style.scenarioFormat.ifBlank { "{{scenario}}" })
                    "personaDescription" -> if(config.persona.isNotBlank()) segments += Segment(raw="玩家身份说明：${config.persona}")
                    "worldInfoBefore", "worldInfoAfter" -> {
                        val content=loreText(if(block.marker=="worldInfoBefore") "before_char" else "after_char")
                        if(content.isNotBlank()) segments += Segment(raw=style.worldInfoFormat.ifBlank { "{0}" }.replace("{0}",content))
                    }
                    else -> if(block.content.isNotBlank()) segments += Segment(block.role,block.content)
                }
            }
            if(!historyAdded) { segments += Segment(history=true); warnings += "预设未启用聊天历史占位块，已在末尾补入本轮聊天" }
            if(sortedLore.any { it.entry.position in setOf("before_char","after_char") } &&
                styleBlocks.none { it.marker in setOf("worldInfoBefore","worldInfoAfter") }) warnings += "世界书前后占位块未启用，相关背景不会发送；聊天深度条目仍按深度插入"
            // Card-specific instructions remain independent of community preset order.
            segments += characterBlocks.filter { it.position=="after_char" && it.content.isNotBlank() }.map { Segment(it.role,it.content) }
        } else {
            val blocks=characterBlocks + styleBlocks
            segments += blocks.filter { it.position=="before_char" && it !in characterBlocks }.map { Segment(it.role,it.content) }
            segments += loreSegments("before_char")
            segments += Segment(raw=buildString {
                appendLine("角色：${card.name}")
                if(card.description.isNotBlank()) appendLine("角色描述：${card.description}")
                if(card.personality.isNotBlank()) appendLine("性格：${card.personality}")
                if(card.scenario.isNotBlank()) appendLine("场景：${card.scenario}")
                if(config.persona.isNotBlank()) appendLine("玩家身份说明：${config.persona}")
            })
            segments += loreSegments("before_examples")
            if(card.exampleDialogue.isNotBlank()) segments += Segment(raw=card.exampleDialogue,examples=true)
            segments += loreSegments("after_examples")
            segments += blocks.filter { it.position=="after_char" }.map { Segment(it.role,it.content) }
            segments += loreSegments("after_char")
            segments += Segment(history=true)
        }
        // Expand in relative prompt order. Variables are reset for each request.
        val expanded=segments.map { segment -> if(segment.history) segment else segment.copy(raw=expander.expand(segment.raw)) }
        val expandedDepth=depth.map { it.copy(raw=expander.expand(it.raw)) }.filter { it.raw.isNotBlank() }
        val fixed=expanded.filter { !it.history }.sumOf { it.raw.length } + expandedDepth.sumOf { it.raw.length } + style?.newChatPrompt.orEmpty().length
        require(fixed < config.historyBudgetChars) { "角色与固定资料超过上下文字符预算，请减少资料或在会话设定中提高预算" }
        val recent=valid.takeLast(200)
        var available=config.historyBudgetChars-fixed
        val dialogue=mutableListOf<PromptMessage>()
        for(index in recent.indices.reversed()) {
            val message=recent[index]
            val content=transform(message.content,message.role,recent.lastIndex-index)
            if(content.length > available) {
                require(dialogue.isNotEmpty()) { "最新消息超过上下文字符预算，请缩短消息或提高会话预算" }; break
            }
            dialogue.add(0,PromptMessage(message.role,content)); available -= content.length
        }
        val insertions=expandedDepth.groupBy { (dialogue.size-it.depth).coerceAtLeast(0) }
        val withDepth=buildList {
            for(i in 0..dialogue.size) {
                insertions[i]?.sortedWith(compareBy<Insertion> { when(it.role) { "user"->0; "assistant"->1; else->2 } }.thenBy { it.order })?.forEach { add(PromptMessage(it.role,it.raw)) }
                if(i < dialogue.size) add(dialogue[i])
            }
        }
        val result=buildList {
            for(segment in expanded) {
                when {
                    segment.history -> { style?.newChatPrompt?.takeIf(String::isNotBlank)?.let { add(PromptMessage("system",expander.expand(it))) }; addAll(withDepth) }
                    segment.examples -> addAll(exampleMessages(segment.raw,card.name,user,style?.newExamplePrompt.orEmpty(),expander))
                    segment.raw.isNotBlank() -> add(PromptMessage(segment.role,segment.raw))
                }
            }
        }
        require(result.sumOf { it.content.length } <= config.historyBudgetChars) { "宏或示例展开后超出上下文预算，请减少资料或提高预算" }
        if(config.references().any { id -> assets.none { it.id==id } }) warnings += "有绑定资料未找到，已跳过；可重新选择资料"
        return PromptReport(result,lore.entries.map { "${it.bookName} / ${it.entry.name}（${it.reason}）" },(warnings+expander.warnings).distinct(),valid.size-dialogue.size)
    }

    private fun exampleMessages(text: String, char: String, user: String, template: String, expander: MacroExpander): List<PromptMessage> {
        if(text.isBlank()) return emptyList()
        val result=mutableListOf<PromptMessage>()
        for(chunk in text.split(Regex("(?i)<START>"))) {
            if(chunk.isBlank()) continue
            val pattern=Regex("(?m)^("+Regex.escape(user)+"|"+Regex.escape(char)+"):\\s*")
            val matches=pattern.findAll(chunk).toList()
            if(template.isNotBlank()) result += PromptMessage("system",expander.expand(template))
            if(matches.isEmpty()) result += PromptMessage("system","对话风格示例，不属于当前剧情：\n$chunk")
            else {
                val prefix=chunk.substring(0,matches.first().range.first).trim()
                if(prefix.isNotBlank()) result += PromptMessage("system",prefix)
                matches.forEachIndexed { index,match ->
                    val content=chunk.substring(match.range.last+1,matches.getOrNull(index+1)?.range?.first ?: chunk.length).trim()
                    if(content.isNotBlank()) result += PromptMessage(if(match.groupValues[1]==user) "user" else "assistant",content)
                }
            }
        }
        return result
    }

    private data class Hit(val bookId: String,val bookName: String,val entry: LoreEntry,val content: String,val reason: String)
    private data class LoreResult(val entries: List<Hit>,val warnings: List<String>)
    private fun selectLore(config: RoleplayConfig,assets: List<ResourceAsset>,history: List<ChatMessage>,card: CharacterCard,userName: String,
        transform: (String)->String): LoreResult {
        val charName=card.name
        val books = config.lorebookIds.mapNotNull { id -> assets.firstOrNull { it.id==id && it.kind=="lorebook" && it.enabled } }
            .map { it to appJson.decodeFromString<LoreBook>(it.payload) }
        val hits = mutableListOf<Hit>();val warnings = mutableListOf<String>();val seen=mutableSetOf<String>()
        var total=0; val perBook=mutableMapOf<String,Int>();val deadline=System.nanoTime()+1_000_000_000
        fun matches(key: String,text: String,sensitive: Boolean,whole: Boolean): Boolean {
            val expanded=key.replace("{{char}}",charName).replace("{{user}}",userName)
            if(expanded.startsWith('/')) return RegexEngine.matches(expanded,text)
            if(expanded.isBlank()) return false
            if(!whole || expanded.any { it.isWhitespace() }) return text.contains(expanded,!sensitive)
            var from=0
            while(from < text.length) {
                val index=text.indexOf(expanded,from,!sensitive)
                if(index < 0) return false
                val end=index+expanded.length
                fun word(c: Char)=c.isLetterOrDigit() || c=='_'
                if((index==0 || !word(text[index-1])) && (end==text.length || !word(text[end]))) return true
                from=index+1
            }
            return false
        }
        for (pass in 0..3) {
            val candidates = mutableListOf<Hit>()
            for((asset,book) in books) for(entry in book.entries) {
                if (System.nanoTime()>deadline) {warnings+="世界书扫描达到时间预算，剩余条目未扫描";return LoreResult(hits,warnings)}
                if(!entry.enabled || entry.probability == 0 || "${asset.id}:${entry.id}" in seen || (pass>0 && (!book.recursive || entry.excludeRecursion))) continue
                val depth=entry.scanDepth ?: config.scanDepth.takeUnless { it==4 } ?: book.scanDepth
                val extra=listOfNotNull(config.persona.takeIf { entry.matchPersona },card.description.takeIf { entry.matchDescription },
                    card.personality.takeIf { entry.matchPersonality },card.scenario.takeIf { entry.matchScenario }).joinToString("\n")
                val scan=extra+"\n"+history.takeLast(depth).joinToString("\n") { "${if(it.role=="user") userName else charName}: ${it.content}" } +
                    if(pass>0) hits.filter { !it.entry.preventRecursion }.joinToString("\n") { it.content } else ""
                val primary=entry.constant || entry.keys.any { matches(it,scan,entry.caseSensitive,entry.matchWholeWords) }
                val secondary=entry.secondaryKeys.map { matches(it,scan,entry.caseSensitive,entry.matchWholeWords) }
                val filter=entry.constant || !entry.selective || secondary.isEmpty() || when(entry.logic) {
                    "and_all"->secondary.all{it};"not_any"->secondary.none{it};"not_all"->!secondary.all{it};else->secondary.any{it}
                }
                if(primary && filter && entry.content.isNotBlank()) {
                    val seed=(asset.id+entry.id+history.joinToString { it.content }).hashCode()
                    if(kotlin.random.Random(seed).nextInt(100) >= entry.probability) { seen+="${asset.id}:${entry.id}"; continue }
                    candidates += Hit(asset.id,asset.name,entry,transform(entry.content),
                    if(entry.constant) "常驻" else if(pass==0) "关键词" else "递归")
                }
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
