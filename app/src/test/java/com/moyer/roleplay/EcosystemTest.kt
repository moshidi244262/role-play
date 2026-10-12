package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class EcosystemTest {
    private val card=CharacterCard(name="林舟",description="独特人物设定",personality="安静",scenario="王城",exampleDialogue="<START>\n{{user}}: 早安\n{{char}}: 你好\n<START>\n{{user}}: 来信了\n{{char}}: 我来看看")
    private fun session(config: RoleplayConfig)=ChatSession(id="stable",title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=config.json())
    private fun history(text: String="来到王城")=listOf(ChatMessage(sessionId="stable",role="user",content=text,position=0))
    private fun preset(json: String)=CommunityImporter.decode(json.toByteArray(),"sample.json","preset").resources.single()

    @Test fun strictEntrypointsRejectWrongKindsAndMalformedJson() {
        assertThrows(IllegalArgumentException::class.java) {CommunityImporter.decode("{\"entries\":[]}".toByteArray(),expectedKind="preset")}
        assertThrows(IllegalArgumentException::class.java) {CommunityImporter.decode("{\"prompts\":[{\"content\":\"write\"}]}".toByteArray(),expectedKind="lorebook")}
        assertThrows(IllegalArgumentException::class.java) {CommunityImporter.decode("{broken".toByteArray())}
        assertEquals("lorebook",CommunityImporter.decode("{\"entries\":[]}".toByteArray(),expectedKind="lorebook").resources.single().kind)
    }

    @Test fun nestedVariablesExpandInOrderResetPerRequestAndPreserveUnknownMacros() {
        val macro=MacroExpander(mapOf("user" to "阿岚","char" to "林舟"),9)
        assertEquals("你好阿岚",macro.expand("{{setvar::greet::你好{{USER}}}}{{getvar::greet}}"))
        assertEquals("1",macro.expand("{{setvar::count::0}}{{incvar::count}}"))
        assertEquals("",MacroExpander(emptyMap()).expand("{{getvar::greet}}"))
        assertEquals("{{plugin::value}}",macro.expand("{{plugin::value}}"))
        assertTrue(macro.warnings.any {it.contains("未支持")})
        assertEquals("ab",macro.expand("a\n{{trim}}\n b"))
        assertEquals("abc",macro.expand("a{{// do not send}}bc"))
    }

    @Test fun recursiveMacrosAndGrowthAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {MacroExpander(mapOf("description" to "{{description}}")).expand("{{description}}")}
        assertThrows(IllegalArgumentException::class.java) {MacroExpander(emptyMap()).expand("{{setvar::x::"+"x".repeat(1_000_001)+"}}")}
    }

    @Test fun orderedPresetKeepsPostHistoryTextAndUnlistedBlocksDisabled() {
        val asset=preset("""{"prompts":[{"identifier":"init","name":"初始化","content":"{{setvar::style::轻快}}"},{"identifier":"charDescription","marker":true},{"identifier":"chatHistory","marker":true},{"identifier":"end","content":"写法：{{getvar::style}}"},{"identifier":"unused","enabled":true,"content":"不应出现"}],"prompt_order":[{"character_id":100001,"order":[{"identifier":"init","enabled":true},{"identifier":"charDescription","enabled":true},{"identifier":"chatHistory","enabled":true},{"identifier":"end","enabled":true}]}]}""")
        val report=PromptBuilder.compile(session(RoleplayConfig(presetId=asset.id)),history(),listOf(asset))
        assertEquals("写法：轻快",report.messages.last().content)
        assertEquals(1,report.messages.count {it.content.contains("独特人物设定")})
        assertFalse(report.messages.any {it.content.contains("不应出现")})
        assertTrue(report.messages.indexOfFirst {it.content=="来到王城"} < report.messages.indexOfFirst {it.content=="写法：轻快"})
        assertFalse(report.readable().contains("{{setvar"))
    }

    @Test fun missingHistoryMarkerDoesNotDropUserRequestAndTriggersFollowGenerationType() {
        val asset=preset("""{"prompts":[{"identifier":"a","content":"普通要求","injection_trigger":["normal"]},{"identifier":"b","content":"续写要求","injection_trigger":["continue"]}]}""")
        val config=RoleplayConfig(presetId=asset.id)
        val normal=PromptBuilder.compile(session(config),history(),listOf(asset))
        assertTrue(normal.messages.any {it.content=="普通要求"});assertFalse(normal.messages.any {it.content=="续写要求"})
        assertEquals("来到王城",normal.messages.last().content);assertTrue(normal.warnings.any {it.contains("历史占位块")})
        val continued=PromptBuilder.compile(session(config),history(),listOf(asset),"continue")
        assertTrue(continued.messages.any {it.content=="续写要求"});assertFalse(continued.messages.any {it.content=="普通要求"})
    }

    @Test fun embeddedFortyRegexRulesLoadAndFollowPresetWithoutSeparateBinding() {
        val scripts=(0..39).joinToString(",") {"""{"scriptName":"规则$it","findRegex":"/秘密/g","replaceString":"公开","placement":[2],"markdownOnly":true,"disabled":${it>0}}"""}
        val asset=preset("""{"prompts":[{"content":"保持简洁"}],"extensions":{"regex_scripts":[$scripts],"tavern_helper":{"api_key":"redacted","code":"ignored"}}}""")
        val value=appJson.decodeFromString<StylePreset>(asset.payload)
        assertEquals(40,value.regexRules.size)
        val rules=PromptBuilder.rules(RoleplayConfig(presetId=asset.id),listOf(asset))
        assertEquals(1,rules.size)
        assertEquals("公开",RegexEngine.apply("秘密",rules,"assistant",true).text)
        assertEquals("秘密",RegexEngine.apply("秘密",rules,"assistant",false).text)
        assertTrue(PromptBuilder.rules(RoleplayConfig(presetId=asset.id),listOf(asset.copy(enabled=false))).isEmpty())
        assertFalse(asset.sourceJson.contains("redacted"));assertTrue(asset.notes.contains("不执行"))
    }

    @Test fun largePresetRetainsTwoHundredAndTwentyBlocksAndInactiveSections() {
        val prompts=(0..219).joinToString(",") {"""{"identifier":"p$it","name":"块$it","content":"内容$it"}"""}
        val asset=preset("""{"prompts":[$prompts],"prompt_order":[{"order":[{"identifier":"p219","enabled":true}]}]}""")
        val value=appJson.decodeFromString<StylePreset>(asset.payload)
        assertEquals(220,value.blocks.size);assertEquals(1,value.blocks.count {it.enabled});assertEquals("块219",value.blocks.first().name)
        asset.validate()
    }

    @Test fun exampleMarkerParsesUserAndAssistantTurnsWithoutDuplicatingCard() {
        val asset=preset("""{"prompts":[{"identifier":"dialogueExamples","marker":true},{"identifier":"chatHistory","marker":true}]}""")
        val report=PromptBuilder.compile(session(RoleplayConfig(presetId=asset.id,playerName="阿岚")),history(),listOf(asset))
        assertEquals(listOf("早安","你好","来信了","我来看看","来到王城"),report.messages.filter {it.role!="system"}.map {it.content})
    }

    @Test fun wholeWordProbabilityAndCharacterScanningWorkTogether() {
        val book=LoreBook(entries=listOf(LoreEntry(name="英文",keys=listOf("king"),matchWholeWords=true,content="王的规则"),
            LoreEntry(name="场景",keys=listOf("王城"),matchScenario=true,content="钟楼六点报时"),
            LoreEntry(name="零概率",constant=true,probability=0,content="绝不出现")))
        val asset=ResourceAsset(kind="lorebook",name="书",payload=appJson.encodeToString(book))
        val config=RoleplayConfig(lorebookIds=listOf(asset.id))
        val miss=PromptBuilder.compile(session(config),history("liking"),listOf(asset))
        assertEquals(1,miss.hits.size);assertTrue(miss.hits.single().contains("场景"))
        val hit=PromptBuilder.compile(session(config),history("the king arrives"),listOf(asset))
        assertEquals(2,hit.hits.size);assertFalse(hit.readable().contains("绝不出现"))
    }

    @Test fun helpSearchCoversAliasesBodyAndMultipleTerms() {
        assertTrue(HelpCatalog.search("世界树").any {it.id=="lore"})
        assertTrue(HelpCatalog.search("思维链").any {it.id=="cot"})
        assertTrue(HelpCatalog.search("setvar").any {it.id=="macros"})
        assertTrue(HelpCatalog.search("角色卡", "核心资料").all {it.category=="核心资料"})
        assertTrue(HelpCatalog.search("不存在的搜索词xyz").isEmpty())
        assertEquals(HelpCatalog.articles.size,HelpCatalog.search("").size)
    }

    @Test fun thinkTagsAreCollapsedAndMarkupRemainsScriptFree() {
        val blocks=MessageMarkup.parse("<think>生成的说明<script>alert(1)</script></think><content>正文</content>")
        val folded=blocks.filterIsInstance<MessageBlock.Disclosure>().single()
        assertFalse(folded.open);assertTrue(folded.title.contains("思考说明"))
        assertFalse(blocks.toString().contains("alert"));assertTrue(blocks.toString().contains("正文"))
    }

    @Test fun newFieldsSurviveBackupRemappingAndOldPayloadsStillDecode() {
        val asset=preset("""{"prompts":[{"content":"{{setvar::x::a}}{{getvar::x}}"}],"extensions":{"regex_scripts":[{"findRegex":"/a/g","replaceString":"b"}]}}""")
        val configured=card.copy(config=RoleplayConfig(presetId=asset.id).json())
        val bundle=BackupBundle(characters=listOf(configured),sessions=emptyList(),messages=emptyList(),resources=listOf(asset))
        val restored=BackupCodec.remap(BackupCodec.decode(BackupCodec.encode(bundle)))
        assertEquals(restored.resources.single().id,readConfig(restored.characters.single().config).presetId)
        assertEquals(1,appJson.decodeFromString<StylePreset>(restored.resources.single().payload).regexRules.size)
        assertFalse(appJson.decodeFromString<StylePreset>("{\"blocks\":[]}").ordered)
    }
}

/** Run against a user-owned local file after downloading the CI-compiled runtime. Prints counts only. */
object CommunityProbe {
    @JvmStatic fun main(args: Array<String>) {
        val file=java.io.File(args.single())
        val pack=CommunityImporter.decode(file.readBytes(),file.name,"preset")
        val asset=pack.resources.single()
        val preset=appJson.decodeFromString<StylePreset>(asset.payload)
        val card=CharacterCard(name="测试角色",description="在王城的信使",personality="沉稳",scenario="钟楼",greeting="你好")
        val config=RoleplayConfig(presetId=asset.id,playerName="阿岚",historyBudgetChars=200000)
        val session=ChatSession(id="probe",title="测试",characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=config.json())
        val report=PromptBuilder.compile(session,listOf(ChatMessage(sessionId=session.id,role="user",content="我走进钟楼。",position=0)),pack.resources)
        check(report.messages.any {it.content=="我走进钟楼。"})
        check(report.messages.none {it.content.contains("{{setvar") || it.content.contains("{{getvar")})
        println("blocks=${preset.blocks.size}; enabled=${preset.blocks.count {it.enabled}}; markers=${preset.blocks.count {it.marker.isNotBlank()}}; regex=${preset.regexRules.size}; enabledRegex=${preset.regexRules.count {it.enabled}}; requestMessages=${report.messages.size}; chars=${report.messages.sumOf {it.content.length}}; warnings=${report.warnings.size}")
        println("Unsupported/compatibility warning count=${pack.warnings.size}; variable expansion and current user message verified")
    }
}
