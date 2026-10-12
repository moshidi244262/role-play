package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class StoryMemoryTest {
    private val card=CharacterCard(name="林舟",description="守护旧城档案馆")
    private fun history(count: Int=40)=(0 until count).map {ChatMessage(sessionId="story",position=it.toLong(),role=if(it%2==0) "user" else "assistant",content="消息$it：在雨夜找到第${it}条线索。")}
    private fun session(config: RoleplayConfig)=ChatSession(id="story",title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=config.json())

    @Test fun planRetainsRecentTwelveAndIncrementalSummaryDoesNotResubmitAllOldText() {
        val messages=history()
        val plan=StoryMemory.plan(messages,null,20)!!
        assertEquals(28,plan.added.size)
        val summary=plan.summary("已找到蓝色钥匙，尚未打开西门。")
        val extended=history(70)
        val update=StoryMemory.plan(extended,summary,20)!!
        assertEquals(28L,update.added.first().position)
        assertEquals(57L,update.added.last().position)
        assertEquals(58,update.sources.size)
        assertTrue(update.prompt().last().content.contains(summary.text))
        assertFalse(update.prompt().last().content.contains("消息0："))
        assertNull(StoryMemory.plan(messages,summary,20))
    }

    @Test fun editsRemovalAndOlderForkInvalidateSummaryWhileIdRemappingDoesNot() {
        val messages=history()
        val summary=StoryMemory.plan(messages,null)!!.summary("钥匙在林舟手中。")
        assertEquals(summary,StoryMemory.valid(summary,messages.map {it.copy(id=newId(),sessionId="fork")}))
        assertNull(StoryMemory.valid(summary,messages.take(20)))
        assertNull(StoryMemory.valid(summary,messages.map {if(it.position==5L) it.copy(content="钥匙丢失") else it}))
        assertNull(StoryMemory.valid(summary,messages.filter {it.position!=8L}))
        assertEquals(summary,StoryMemory.valid(summary,messages.map {if(it.position==35L) it.copy(content="新的选择") else it}))
    }

    @Test fun explicitCompressionReplacesOnlyCoveredCompleteSourcesAndPreservesLocalHistory() {
        val messages=history()+ChatMessage(sessionId="story",position=40,role="assistant",content="未完成片段",status="interrupted")
        val summary=StoryMemory.plan(messages,null)!!.summary("旧城已有28条线索。")
        val config=RoleplayConfig(storySummary=summary,compactSummaryHistory=true,historyBudgetChars=0,memory="必须保留的承诺")
        val report=PromptBuilder.compile(session(config),messages)
        assertEquals(28,report.summarizedMessages)
        assertEquals(0,report.trimmedMessages)
        assertFalse(report.messages.any {it.content==messages.first().content})
        assertTrue(report.messages.any {it.content.contains(summary.text)})
        assertTrue(report.messages.any {it.content.contains("必须保留的承诺")})
        assertTrue(report.messages.any {it.content=="未完成片段"})
        assertEquals(41,messages.size)
        val full=PromptBuilder.compile(session(config.copy(compactSummaryHistory=false)),messages)
        assertEquals(0,full.summarizedMessages)
        assertTrue(full.messages.any {it.content==messages.first().content})
    }

    @Test fun invalidSummaryNeverCompressesHistoryOrLeaksFutureFacts() {
        val messages=history()
        val summary=StoryMemory.plan(messages,null)!!.summary("未来的西门已经打开")
        val report=PromptBuilder.compile(session(RoleplayConfig(storySummary=summary,compactSummaryHistory=true)),messages.take(10))
        assertEquals(0,report.summarizedMessages)
        assertFalse(report.messages.any {it.content.contains(summary.text)})
        assertTrue(report.warnings.any {it.contains("来源已变化")})
    }

    @Test fun oversizedSourceIsNotSplitOrSilentlySkippedAndIncompleteSourcesAreNotSummarized() {
        val messages=history().map {if(it.position==0L) it.copy(content="旧".repeat(60001)) else it}
        assertNull(StoryMemory.plan(messages,null))
        val partial=history().map {if(it.position==3L) it.copy(status="interrupted") else it}
        val plan=StoryMemory.plan(partial,null)!!
        assertFalse(plan.sources.any {it.position==3L})
        val summary=plan.summary("已发生的事实")
        assertNotNull(StoryMemory.valid(summary,partial))
    }

    @Test fun backupRestorePreservesSummaryAndNewStoryNeverInheritsGeneratedPlot() {
        val messages=history()
        val summary=StoryMemory.plan(messages,null)!!.summary("林舟保存了钥匙")
        val config=RoleplayConfig(storySummary=summary,autoSummary=true,compactSummaryHistory=true)
        val restored=BackupCodec.remap(BackupCodec.decode(BackupCodec.encode(BackupBundle(characters=listOf(card),sessions=listOf(session(config)),messages=messages))))
        assertNotNull(StoryMemory.valid(readConfig(restored.sessions.single().config).storySummary,restored.messages))
        assertNull(configDefaults(config,RoleplayConfig()).storySummary)
        assertTrue(configDefaults(config,RoleplayConfig()).autoSummary)
    }

    @Test fun summarySizeAndMalformedProvenanceCannotBeSaved() {
        val summary=StoryMemory.plan(history(),null)!!.summary("事实")
        assertTrue(runCatching {summary.copy(text=" ").validate()}.isFailure)
        assertTrue(runCatching {summary.copy(sourceDigest="wrong").validate()}.isFailure)
        assertTrue(runCatching {summary.copy(text="长".repeat(16001)).validate()}.isFailure)
        assertFalse(RoleplayConfig().autoSummary)
        assertFalse(RoleplayConfig().compactSummaryHistory)
    }

    @Test fun pausedMemoryStopsInjectionAndCompressionWithoutChangingImportedResourcesOrHistory() {
        val messages=history()
        val summary=StoryMemory.plan(messages,null)!!.summary("内置摘要的剧情")
        val preset=ResourceAsset(kind="preset",name="外部记忆提示",sourceJson="{\"original\":true}",
            payload=appJson.encodeToString(StylePreset(blocks=listOf(PromptBlock(content="外部记忆：{{setvar::external_memory::保留预设事实}}{{getvar::external_memory}}")))))
        val lore=ResourceAsset(kind="lorebook",name="外部背景",payload=appJson.encodeToString(LoreBook(listOf(LoreEntry(constant=true,content="保留世界书事实")))))
        val config=RoleplayConfig(memory="内置固定记忆",storySummary=summary,autoSummary=true,compactSummaryHistory=true,
            builtInMemoryEnabled=false,historyBudgetChars=0,presetId=preset.id,lorebookIds=listOf(lore.id))
        val original=messages.toList();val resources=listOf(preset,lore)
        val report=PromptBuilder.compile(session(config),messages,resources)
        assertEquals(0,report.summarizedMessages);assertEquals(0,report.trimmedMessages)
        assertFalse(report.messages.any {it.content.contains("内置固定记忆") || it.content.contains(summary.text)})
        assertTrue(report.messages.any {it.content=="外部记忆：保留预设事实"})
        assertTrue(report.messages.any {it.content.contains("保留世界书事实")})
        messages.forEach {message -> assertTrue(report.messages.any {it.content==message.content})}
        assertEquals(original,messages);assertEquals(preset,resources.first())
        assertTrue(report.memoryStatus.contains("已暂停"))
        val resumed=PromptBuilder.compile(session(config.copy(builtInMemoryEnabled=true)),messages,resources)
        assertEquals(28,resumed.summarizedMessages)
        assertTrue(resumed.messages.any {it.content.contains(summary.text)})
        assertTrue(resumed.messages.any {it.content.contains(config.memory)})
    }

    @Test fun memoryFactsRemainLiteralAndCannotSetOrReadPresetVariables() {
        val messages=history()
        val fixed="{{setvar::external_memory::不应执行}} {{plugin_summary}}"
        val summary=StoryMemory.plan(messages,null)!!.summary("{{setvar::other_memory::也不应执行}}")
        val preset=ResourceAsset(kind="preset",name="变量检查",payload=appJson.encodeToString(StylePreset(
            blocks=listOf(PromptBlock(content="A：{{getvar::external_memory}}；B：{{getvar::other_memory}}")))))
        val report=PromptBuilder.compile(session(RoleplayConfig(memory=fixed,storySummary=summary,presetId=preset.id)),messages,listOf(preset))
        assertTrue(report.messages.any {it.content.contains(fixed)})
        assertTrue(report.messages.any {it.content.contains(summary.text)})
        assertTrue(report.messages.any {it.content=="A：；B："})
        assertFalse(report.warnings.any {it.contains("plugin_summary")})
    }

    @Test fun pausedGenerationCannotRunManuallyOrAutomaticallyEvenWithSavedAutomaticPreference() {
        val messages=history()
        val paused=RoleplayConfig(builtInMemoryEnabled=false,autoSummary=true,compactSummaryHistory=true)
        assertNull(StoryMemory.generationPlan(messages,paused))
        assertNull(StoryMemory.generationPlan(messages,paused,automatic=true))
        assertNotNull(StoryMemory.generationPlan(messages,paused.copy(builtInMemoryEnabled=true),automatic=true))
        assertNull(StoryMemory.generationPlan(messages,RoleplayConfig(),automatic=true))
        assertNotNull(StoryMemory.generationPlan(messages,RoleplayConfig()))
    }

    @Test fun pauseSurvivesBackupAndNewStoryDefaultsWhileLegacyConfigKeepsExistingBehavior() {
        val messages=history();val summary=StoryMemory.plan(messages,null)!!.summary("独立存储的旧剧情")
        val config=RoleplayConfig(memory="保留固定记忆",storySummary=summary,builtInMemoryEnabled=false,autoSummary=true,compactSummaryHistory=true)
        val restored=BackupCodec.remap(BackupCodec.decode(BackupCodec.encode(BackupBundle(characters=listOf(card),sessions=listOf(session(config)),messages=messages))))
        val saved=readConfig(restored.sessions.single().config)
        assertFalse(saved.builtInMemoryEnabled);assertTrue(saved.autoSummary);assertTrue(saved.compactSummaryHistory)
        assertEquals(config.memory,saved.memory)
        assertNotNull(StoryMemory.valid(saved.storySummary,restored.messages))
        assertFalse(configDefaults(config,RoleplayConfig()).builtInMemoryEnabled)
        assertFalse(configDefaults(RoleplayConfig(),config).builtInMemoryEnabled)
        assertNull(configDefaults(config,RoleplayConfig()).storySummary)
        assertTrue(readConfig("{}").builtInMemoryEnabled)
    }

    @Test fun worldbookDiagnosticsExplainDisabledKeywordsSecondaryProbabilityAndBudget() {
        val entries=listOf(LoreEntry(name="停用",enabled=false),LoreEntry(name="地点",keys=listOf("西门"),content="西门设定"),
            LoreEntry(name="辅助",keys=listOf("雨夜"),selective=true,secondaryKeys=listOf("城外"),content="城外资料"),
            LoreEntry(name="概率",probability=0,constant=true,content="不应发送"),LoreEntry(name="预算",constant=true,content="很长的背景".repeat(20)))
        val book=ResourceAsset(kind="lorebook",name="旧城",payload=appJson.encodeToString(LoreBook(entries)))
        val report=PromptBuilder.compile(session(RoleplayConfig(lorebookIds=listOf(book.id),loreBudget=1)),history(),listOf(book))
        val details=report.loreDiagnostics.joinToString("\n")
        assertTrue(details.contains("条目已停用"));assertTrue(details.contains("关键词未命中"))
        assertTrue(details.contains("辅助关键词条件未满足"));assertTrue(details.contains("触发概率为0%"))
        assertTrue(details.contains("会话总预算不足"))
    }

    @Test fun cardReportSeparatesReadableContentFromUnsupportedExecution() {
        val text="""{"spec":"chara_card_v2","data":{"name":"林舟","description":"档案员","first_mes":"<details><summary>状态</summary>雨夜</details>","extensions":{"mvu":{"variable":"x"}}}}"""
        val report=CharacterCardInspector.inspect(text.toByteArray()).sections.first {it.title=="兼容性报告"}.body
        assertTrue(report.contains("已转换"));assertTrue(report.contains("MVU"));assertTrue(report.contains("当前不执行"))
        assertEquals("状态 雨夜",MessageMarkup.preview("<details><summary>状态</summary>雨夜</details><script>hidden()</script>"))
    }
}
