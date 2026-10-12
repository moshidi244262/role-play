package com.moyer.roleplay.data

import androidx.room.*
import com.moyer.roleplay.core.appJson
import com.moyer.roleplay.core.StorySummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
@Entity(tableName = "resources")
data class ResourceAsset(
    @PrimaryKey val id: String = newId(), val kind: String, val name: String, val payload: String,
    val sourceJson: String = "", val notes: String = "", val enabled: Boolean = true, val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class StoredSetting(@PrimaryKey val id: String, val value: String)

@Serializable
data class RoleplayConfig(
    val lorebookIds: List<String> = emptyList(), val presetId: String? = null,
    val regexIds: List<String> = emptyList(), val characterPromptId: String? = null, val playerName: String = "",
    val persona: String = "", val memory: String = "", val authorNote: String = "", val authorDepth: Int = 2,
    val scanDepth: Int = 4, val loreBudget: Int = 2048, val historyBudgetChars: Int = 48000,
    val narrativeView: String = "auto", val modelOverride: String = "",
    val storySummary: StorySummary? = null, val autoSummary: Boolean = false, val compactSummaryHistory: Boolean = false,
    val builtInMemoryEnabled: Boolean = true
) {
    fun references() = (lorebookIds + regexIds + listOfNotNull(presetId, characterPromptId)).toSet()
    fun remap(ids: Map<String, String>) = copy(lorebookIds = lorebookIds.mapNotNull { ids[it] },
        presetId = presetId?.let { ids[it] }, characterPromptId = characterPromptId?.let { ids[it] }, regexIds = regexIds.mapNotNull { ids[it] })
    fun without(id: String) = copy(lorebookIds = lorebookIds.filter { it != id },
        presetId = presetId?.takeUnless { it == id }, characterPromptId = characterPromptId?.takeUnless { it == id }, regexIds = regexIds.filter { it != id })
    fun validate() {
        storySummary?.validate()
        require(narrativeView in setOf("auto", "first", "third") && modelOverride.length <= 200) { "叙述视角或模型 ID 无效" }
        require(lorebookIds.size <= 64 && regexIds.size <= 32 && playerName.length <= 100) { "绑定资料或玩家名称超出限制" }
        require(scanDepth in 0..100 && loreBudget in 0..16384 && authorDepth in 0..100 &&
            (historyBudgetChars == 0 || historyBudgetChars in 2000..2000000)) { "会话预算或扫描深度无效（上下文预算为 0 或 2000～2000000 字符）" }
        require(persona.length <= 16000 && authorNote.length <= 16000 && memory.length <= 32000) { "身份、记忆或作者注释过长" }
    }
}

fun readConfig(value: String): RoleplayConfig = appJson.decodeFromString<RoleplayConfig>(value).also { it.validate() }
fun RoleplayConfig.json(): String = appJson.encodeToString(this)

@Serializable
data class LoreBook(val entries: List<LoreEntry> = emptyList(), val recursive: Boolean = false,
    val scanDepth: Int = 4, val budget: Int = 2048, val followSessionBudget: Boolean? = null)

// Older imports invented a 2048 cap even when the original book had no token_budget.
// Infer only that legacy default; keep explicit file limits and edited non-default caps.
fun ResourceAsset.followsSessionLoreBudget(book: LoreBook): Boolean = book.followSessionBudget ?: (
    book.budget == 2048 && (sourceJson.isBlank() || runCatching {
        val source = appJson.parseToJsonElement(sourceJson) as? JsonObject
        source != null && (source["token_budget"] as? JsonPrimitive)?.intOrNull == null
    }.getOrDefault(false)))

fun ResourceAsset.effectiveLoreBudget(book: LoreBook, sessionBudget: Int): Int =
    if (followsSessionLoreBudget(book)) sessionBudget else book.budget

@Serializable
data class LoreEntry(
    val id: String = newId(), val name: String = "条目", val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(), val selective: Boolean = false,
    val logic: String = "and_any", val constant: Boolean = false, val enabled: Boolean = true,
    val content: String = "", val order: Int = 100, val position: String = "after_char",
    val depth: Int = 4, val role: String = "system", val caseSensitive: Boolean = false,
    val scanDepth: Int? = null, val preventRecursion: Boolean = false, val excludeRecursion: Boolean = false,
    val matchWholeWords: Boolean = false, val probability: Int = 100,
    val matchPersona: Boolean = false, val matchDescription: Boolean = false,
    val matchPersonality: Boolean = false, val matchScenario: Boolean = false
)

@Serializable
data class StylePreset(val blocks: List<PromptBlock> = emptyList(), val ordered: Boolean = false,
    val regexRules: List<TextRule> = emptyList(), val newChatPrompt: String = "",
    val newExamplePrompt: String = "", val worldInfoFormat: String = "{0}",
    val scenarioFormat: String = "{{scenario}}", val personalityFormat: String = "{{personality}}")

@Serializable
data class PromptBlock(val id: String = newId(), val name: String = "风格提示", val content: String = "",
    val enabled: Boolean = true, val role: String = "system", val position: String = "after_char", val depth: Int = 0,
    val marker: String = "", val order: Int = 100, val triggers: List<String> = emptyList())

@Serializable
data class RegexRules(val rules: List<TextRule> = emptyList())

@Serializable
data class TextRule(
    val id: String = newId(), val name: String = "规则", val pattern: String = "", val replacement: String = "",
    val enabled: Boolean = true, val targets: List<String> = listOf("assistant"),
    val display: Boolean = true, val prompt: Boolean = true,
    val minDepth: Int = 0, val maxDepth: Int = -1, val substituteFind: Int = 0,
    val trims: List<String> = emptyList()
)

fun ResourceAsset.validate() {
    require(name.isNotBlank() && name.length <= 200 && payload.length <= 4 * 1024 * 1024) { "资料名称或大小无效" }
    when (kind) {
        "lorebook" -> appJson.decodeFromString<LoreBook>(payload).also { book ->
            require(book.entries.size <= 5000 && book.scanDepth in 0..100 && book.budget in 0..16384) { "世界书数量或预算无效" }
            require(book.entries.map { it.id }.distinct().size == book.entries.size) { "世界书条目 ID 重复" }
            book.entries.forEach {
                require(it.depth in 0..100 && (it.scanDepth == null || it.scanDepth in 0..100) &&
                    it.role in setOf("system", "user", "assistant") && it.position in setOf("before_char", "after_char", "depth", "before_examples", "after_examples", "before_author", "after_author") && it.probability in 0..100 &&
                    it.logic in setOf("and_any", "and_all", "not_any", "not_all") && it.keys.size <= 100 && it.secondaryKeys.size <= 100) { "世界书条目配置无效" }
            }
        }
        "preset" -> appJson.decodeFromString<StylePreset>(payload).also { preset ->
            require(preset.blocks.size <= 1000 && preset.blocks.map { it.id }.distinct().size == preset.blocks.size) { "预设提示块过多或 ID 重复" }
            preset.blocks.forEach { require(it.depth in 0..100 && it.role in setOf("system", "user", "assistant") &&
                it.position in setOf("before_char", "after_char", "depth") &&
                it.triggers.all { trigger -> trigger in setOf("normal", "continue", "regenerate", "swipe") } &&
                it.marker in setOf("", "worldInfoBefore", "worldInfoAfter", "charDescription", "charPersonality", "scenario", "personaDescription", "dialogueExamples", "chatHistory")) { "预设提示块无效" } }
            validateRules(preset.regexRules)
        }
        "regex" -> appJson.decodeFromString<RegexRules>(payload).also { rules ->
            validateRules(rules.rules)
        }
        else -> throw IllegalArgumentException("不支持的资料类型")
    }
}

private fun validateRules(rules: List<TextRule>) {
    require(rules.size <= 256) { "一个规则集最多 256 条正则" }
    rules.forEach { require(it.pattern.length <= 4096 && it.minDepth in 0..10000 && it.maxDepth in -1..10000 &&
        it.targets.all { target -> target in setOf("user", "assistant", "lore") } && it.substituteFind in 0..2) { "正则配置无效" } }
}

fun configDefaults(defaults: RoleplayConfig, card: RoleplayConfig): RoleplayConfig = card.copy(
    lorebookIds = (defaults.lorebookIds + card.lorebookIds).distinct(),
    regexIds = (defaults.regexIds + card.regexIds).distinct(), presetId = card.presetId ?: defaults.presetId,
    playerName = card.playerName.ifBlank { defaults.playerName }, persona = card.persona.ifBlank { defaults.persona },
    narrativeView = if(card.narrativeView == "auto") defaults.narrativeView else card.narrativeView,
    memory=card.memory.ifBlank {defaults.memory},authorNote=card.authorNote.ifBlank { defaults.authorNote },authorDepth=if(card.authorDepth==2) defaults.authorDepth else card.authorDepth,
    scanDepth=if(card.scanDepth==4) defaults.scanDepth else card.scanDepth,loreBudget=if(card.loreBudget==2048) defaults.loreBudget else card.loreBudget,
    historyBudgetChars=if(card.historyBudgetChars==48000) defaults.historyBudgetChars else card.historyBudgetChars,
    storySummary=null, autoSummary=card.autoSummary || defaults.autoSummary, compactSummaryHistory=card.compactSummaryHistory || defaults.compactSummaryHistory,
    builtInMemoryEnabled=card.builtInMemoryEnabled && defaults.builtInMemoryEnabled
)
