package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RoleplayEngineTest {
    private val card=CharacterCard(name="管理员",description="{{char}} 守护书库")
    private fun session(config: RoleplayConfig)=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=config.json())
    private fun messages(vararg content: String)=content.mapIndexed {index,s->ChatMessage(sessionId="s",role=if(index%2==0) "user" else "assistant",content=s,position=index.toLong())}
    private fun book(entries: List<LoreEntry>,recursive: Boolean=false,budget: Int=2048)=ResourceAsset(kind="lorebook",name="背景",payload=appJson.encodeToString(LoreBook(entries,recursive,budget=budget)))

    @Test fun loreCombinesPrimarySecondaryRecursionAndDepth() {
        val asset=book(listOf(LoreEntry(name="门",keys=listOf("书库"),secondaryKeys=listOf("雨"),selective=true,content="钥匙是蓝色",position="before_char"),
            LoreEntry(name="钥匙",keys=listOf("蓝色"),content="能打开西门",position="depth",depth=0)),true)
        val history=messages("雨夜来到书库")
        val report=PromptBuilder.compile(session(RoleplayConfig(lorebookIds=listOf(asset.id))),history,listOf(asset))
        assertEquals(2,report.hits.size)
        assertTrue(report.hits[1].contains("递归"))
        assertTrue(report.messages.last().content.contains("能打开西门"))
        assertTrue(report.messages.indexOfFirst {it.content.contains("钥匙是蓝色")} < report.messages.indexOfFirst {it.content.contains("角色：管理员")})
        assertEquals(0,PromptBuilder.compile(session(RoleplayConfig(lorebookIds=listOf(asset.id))),messages("晴天来到书库"),listOf(asset)).hits.size)
    }

    @Test fun constantsTakeBudgetFirstAndDisabledEntriesNeverActivate() {
        val asset=book(listOf(LoreEntry(name="常驻",constant=true,selective=true,secondaryKeys=listOf("不应限制常驻"),content="甲乙丙丁"),LoreEntry(name="关键词",keys=listOf("门"),content="甲乙丙丁"),
            LoreEntry(name="停用",constant=true,enabled=false,content="不能出现")))
        val report=PromptBuilder.compile(session(RoleplayConfig(lorebookIds=listOf(asset.id),loreBudget=6)),messages("门"),listOf(asset))
        assertEquals(1,report.hits.size);assertTrue(report.hits.single().contains("常驻"))
        assertTrue(report.warnings.any {it.contains("预算不足")})
    }

    @Test fun presetSelectionRetainsCardInstructionsAndPersonaMacros() {
        val embedded=ResourceAsset(kind="preset",name="角色提示",payload=appJson.encodeToString(StylePreset(listOf(PromptBlock(content="守护者原始约定")))))
        val style=ResourceAsset(kind="preset",name="风格",payload=appJson.encodeToString(StylePreset(listOf(PromptBlock(content="{{user}} 是 {{persona}}")))))
        val report=PromptBuilder.compile(session(RoleplayConfig(characterPromptId=embedded.id,presetId=style.id,playerName="旅人",persona="信使")),emptyList(),listOf(embedded,style))
        val joined=report.messages.joinToString {it.content}
        assertTrue(joined.contains("守护者原始约定"));assertTrue(joined.contains("旅人 是 信使"));assertTrue(joined.contains("以角色卡为准"))
    }

    @Test fun historyTrimmingUsesContiguousRecentSuffixAndNeverEditsSource() {
        val history=messages("旧".repeat(1000),"中".repeat(800),"新".repeat(600))
        val report=PromptBuilder.compile(session(RoleplayConfig(historyBudgetChars=2000,memory="已找到 B-17 档案盒")),history)
        assertEquals(1,report.trimmedMessages)
        assertEquals(listOf(history[1].content,history[2].content),report.messages.filter {it.role!="system"}.map {it.content})
        assertEquals(1000,history[0].content.length)
        assertTrue(report.messages.any {it.content.contains("已找到 B-17 档案盒")})
    }

    @Test fun displayAndPromptRulesHaveIndependentScopesAndDepth() {
        val rule=TextRule(pattern="/旧/g",replacement="新",prompt=false)
        assertEquals("新旧",RegexEngine.apply("旧旧",listOf(rule.copy(pattern="旧")),"assistant",true).text)
        assertEquals("新新",RegexEngine.apply("旧旧",listOf(rule),"assistant",true).text)
        assertEquals("旧旧",RegexEngine.apply("旧旧",listOf(rule),"assistant",false).text)
        assertEquals("旧",RegexEngine.apply("旧",listOf(rule.copy(minDepth=1)),"assistant",true,0).text)
        assertEquals("旧",RegexEngine.apply("旧",listOf(rule),"user",true).text)
    }

    @Test fun javascriptReplacementGroupsAndMatchStayLiteral() {
        assertEquals("b-a-ab-$",RegexEngine.apply("ab",listOf(TextRule(pattern="/(a)(b)/",replacement="$2-$1-$&-$$")),"assistant",true).text)
        assertEquals("$1",RegexEngine.apply("[$1]",listOf(TextRule(pattern="/.+/",replacement="{{match}}",trims=listOf("[","]"))),"assistant",true).text)
        assertEquals("玩家",RegexEngine.apply("角色",listOf(TextRule(pattern="/{{char}}/g",replacement="{{user}}",substituteFind=2)),"assistant",true,0,"角色","玩家").text)
        assertEquals("xaax",RegexEngine.apply("aa",listOf(TextRule(pattern="/^|$/g",replacement="x")),"assistant",true).text)
    }

    @Test fun pathologicalRegexDoesNotBacktrackExponentially() {
        val text="a".repeat(30000)+"!"
        val start=System.nanoTime()
        val result=RegexEngine.apply(text,listOf(TextRule(pattern="/(a+)+$/",replacement="bad")),"assistant",true)
        assertEquals(text,result.text)
        assertTrue((System.nanoTime()-start)/1000000<2000)
    }

    @Test fun unsupportedLookaroundIsExplicitlyRejected() {
        assertThrows(IllegalArgumentException::class.java) {RegexEngine.compile("/(?=a)/g")}
        assertTrue(RegexEngine.apply("a",listOf(TextRule(pattern="/(?=a)/g")),"assistant",true).warnings.isNotEmpty())
    }

    @Test fun disabledRulesDoNotUseTheActiveRuleLimitAndOverflowIsReported() {
        val disabled=List(32) {TextRule(enabled=false,pattern="/a/g",replacement="bad")}
        assertEquals("b",RegexEngine.apply("a",disabled+TextRule(pattern="/a/g",replacement="b"),"assistant",true).text)
        assertTrue(RegexEngine.apply("a",List(33) {TextRule(pattern="/z/g",replacement="x")},"assistant",true).warnings.any {it.contains("32")})
    }

    @Test fun v1BackupAndV2ResourceReferencesRoundTripWithoutReusingIds() {
        val asset=book(listOf(LoreEntry(constant=true,content="地图")))
        val configured=card.copy(config=RoleplayConfig(lorebookIds=listOf(asset.id)).json())
        val session=session(readConfig(configured.config)).copy(characterSnapshot=appJson.encodeToString(configured))
        val bundle=BackupBundle(characters=listOf(configured),sessions=listOf(session),messages=emptyList(),resources=listOf(asset))
        val mapped=BackupCodec.remap(BackupCodec.decode(BackupCodec.encode(bundle)))
        assertNotEquals(asset.id,mapped.resources.single().id)
        assertEquals(mapped.resources.single().id,readConfig(mapped.sessions.single().config).lorebookIds.single())
        assertEquals(mapped.resources.single().id,readConfig(appJson.decodeFromString<CharacterCard>(mapped.sessions.single().characterSnapshot).config).lorebookIds.single())
        val legacy=BackupBundle(version=1,characters=listOf(card),sessions=emptyList(),messages=emptyList())
        assertEquals(1,BackupCodec.decode(BackupCodec.encode(legacy)).version)
    }
}
