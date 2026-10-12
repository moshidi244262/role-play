package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReadingFeaturesTest {
    private fun text(blocks: List<MessageBlock>): String = blocks.joinToString("\n") { when(it) {
        is MessageBlock.Paragraph -> it.runs.joinToString("") {run->run.text}
        is MessageBlock.Disclosure -> it.title+"\n"+text(it.children)
    } }

    @Test fun screenshotStyleStatusIsReadableAndCollapsible() {
        val blocks=MessageMarkup.parse("""院中静了一瞬。
            <user_status><div style="background-color:#f5f5f5;padding:16px">
            <details style="cursor:pointer"><summary>引水序（武林排行榜）</summary>
            <div><span>序甲：</span><span>广寂（积香寺高僧）</span></div>
            <div><b>序乙：</b>赵人姬（少樱山前掌门）</div></details></div></user_status>""")
        val shown=text(blocks)
        assertTrue(shown.contains("院中静了一瞬。"));assertTrue(shown.contains("序甲：广寂"))
        assertFalse(shown.contains("style="));assertFalse(shown.contains("<div"))
        val panel=blocks.filterIsInstance<MessageBlock.Disclosure>().single()
        assertEquals("引水序（武林排行榜）",panel.title);assertFalse(panel.open)
    }

    @Test fun markupCannotFetchUrlsExecuteCodeOrExposeScriptText() {
        val shown=text(MessageMarkup.parse("""<style>secret-css</style><script>secret-script</script>
            <iframe src="https://example.com">secret-frame</iframe><a onclick="secret-handler" href="file:///secret">故事</a>
            <img src="https://example.com/private" alt="头像">"""))
        assertTrue(shown.contains("故事"));assertTrue(shown.contains("头像"))
        assertFalse(shown.contains("secret"));assertFalse(shown.contains("https:"))
    }

    @Test fun plainTextAndComparisonsArePreserved() {
        val value="我往后退了一步。\n\n2 < 3，心情 <3。"
        assertEquals(value,text(MessageMarkup.parse(value)))
    }

    @Test fun unfinishedStreamingAttributesAreNotShown() {
        assertEquals("故事",text(MessageMarkup.parse("<div>故事</div><span style=\"background")))
        val shown=text(MessageMarkup.parse("```html\n<details open><summary>人物</summary><b>姓名</b>：林舟</details>\n```"))
        assertTrue(shown.contains("姓名：林舟"));assertFalse(shown.contains("```"))
    }

    @Test fun tableCellsAndBoldRetainStructure() {
        val blocks=MessageMarkup.parse("<table><tr><th>人物</th><th>关系</th></tr><tr><td>林舟</td><td>同伴</td></tr></table>")
        assertEquals("人物 · 关系\n林舟 · 同伴",text(blocks))
        assertTrue((blocks[0] as MessageBlock.Paragraph).runs.first().bold)
    }

    @Test fun characterSearchMatchesAllTermsAcrossFieldsWithoutCaseSensitivity() {
        val a=CharacterCard(name="林舟",scenario="旧城档案馆",personality="Calm")
        val b=CharacterCard(name="沈林",description="山间旅人")
        assertEquals(listOf(a),searchCharacters(listOf(a,b)," 林 CALM "))
        assertEquals(listOf(b),searchCharacters(listOf(a,b),"旅人"))
        assertTrue(searchCharacters(listOf(a,b),"空白角色").isEmpty())
        assertEquals(listOf(a,b),searchCharacters(listOf(a,b),"  "))
    }

    @Test fun defaultIdentityIsFirstPersonAndNamedPersonaIsPreserved() {
        val card=CharacterCard(name="林舟",description="{{char}} 对 {{user}} 说话")
        val session=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card))
        val prompt=PromptBuilder.build(session,emptyList()).joinToString("\n") {it.content}
        assertTrue(prompt.contains("林舟 对 我 说话"));assertTrue(prompt.contains("第一人称"))
        val named=session.copy(config=RoleplayConfig(playerName="沈望",narrativeView="third").json())
        val namedPrompt=PromptBuilder.build(named,emptyList()).joinToString("\n") {it.content}
        assertTrue(namedPrompt.contains("林舟 对 沈望 说话"));assertTrue(namedPrompt.contains("第三人称"))
    }

    @Test fun sessionModelOverrideDoesNotChangeSharedConnectionOrOtherSessions() {
        val connection=ProviderTemplates.all[0]
        assertEquals("deepseek-v4-pro",sessionProfile(connection,RoleplayConfig(modelOverride="deepseek-v4-pro")).model)
        assertEquals("deepseek-flash",sessionProfile(connection,RoleplayConfig()).model)
        assertEquals("deepseek-flash",connection.model)
        val config=RoleplayConfig(narrativeView="first",modelOverride="a-model")
        assertEquals(config,readConfig(config.json()))
        assertEquals("auto",readConfig("{}").narrativeView)
    }
}
