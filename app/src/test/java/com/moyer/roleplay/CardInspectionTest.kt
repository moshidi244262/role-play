package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

class CardInspectionTest {
    private fun root(budget: Int?=null): String = buildJsonObject {
        put("spec","chara_card_v2"); put("spec_version","2.0")
        putJsonObject("data") {
            put("name","测试世界"); put("description","角色说明\n第二行"); put("first_mes","来到引水阁")
            putJsonObject("extensions") {put("unknown_plugin","<script>原文，不执行</script>"); put("api_key","private")}
            putJsonObject("character_book") {
                if(budget != null) put("token_budget",budget)
                put("entries",JsonArray(listOf(
                    buildJsonObject {put("name","排名更新");put("constant",true);put("insertion_order",300);put("content","甲".repeat(400))},
                    buildJsonObject {put("name","温实明");put("constant",true);put("insertion_order",200);put("content","乙".repeat(400))},
                    buildJsonObject {put("name","背景");put("constant",true);put("insertion_order",100);put("content","丙".repeat(1500))},
                    buildJsonObject {put("name","关闭的剧透");put("enabled",false);put("constant",true);put("content","封存内容")}
                )))
            }
        }
    }.toString()

    private fun compile(asset: ResourceAsset, budget: Int): PromptReport {
        val card=CharacterCard(name="测试世界")
        val session=ChatSession(title=card.name,characterId=card.id,characterSnapshot=appJson.encodeToString(card),config=RoleplayConfig(lorebookIds=listOf(asset.id),loreBudget=budget).json())
        return PromptBuilder.compile(session,emptyList(),listOf(asset))
    }

    @Test fun absentBookBudgetFollowsSessionForNewAndLegacyImports() {
        val imported=CommunityImporter.decode(root().toByteArray()).resources.single {it.kind=="lorebook"}
        assertEquals(3,compile(imported,8192).hits.size)
        val oldPayload=JsonObject((appJson.parseToJsonElement(imported.payload) as JsonObject).filterKeys {it!="followSessionBudget"}).toString()
        val legacy=imported.copy(payload=oldPayload)
        assertEquals(2,compile(legacy,2048).hits.size)
        assertEquals(3,compile(legacy,8192).hits.size)
        assertTrue(compile(legacy,8192).readable().contains("3450 / 8192"))
        assertTrue(compile(legacy,8192).readable().contains("跟随会话预算"))
    }

    @Test fun explicitBookCapAndEditedLegacyCapsRemainInForceWithSpecificDiagnostics() {
        val imported=CommunityImporter.decode(root(2048).toByteArray()).resources.single {it.kind=="lorebook"}
        val report=compile(imported,8192)
        assertEquals(2,report.hits.size)
        assertTrue(report.warnings.single().contains("已触发，需约 2250 tokens"))
        assertTrue(report.warnings.single().contains("本书预算不足（已用 1200 / 2048）"))
        assertFalse(report.warnings.single().contains("会话总预算不足"))
        val payload=appJson.decodeFromString<LoreBook>(imported.payload).copy(followSessionBudget=null,budget=3000)
        val edited=imported.copy(payload=appJson.encodeToString(payload),sourceJson="{}")
        assertEquals(2,compile(edited,8192).hits.size)
        assertEquals(3,compile(imported.copy(payload=appJson.encodeToString(payload.copy(followSessionBudget=true))),8192).hits.size)
    }

    @Test fun zeroAndSessionGlobalLimitsNeverBecomeUnlimited() {
        val pack=CommunityImporter.decode(root().toByteArray());val asset=pack.resources.single {it.kind=="lorebook"}
        assertEquals(0,compile(asset,0).hits.size)
        assertTrue(compile(asset,0).warnings.all {it.contains("会话总预算不足")})
        val zero=CommunityImporter.decode(root(0).toByteArray()).resources.single {it.kind=="lorebook"}
        assertEquals(0,compile(zero,8192).hits.size)
        val malformed=asset.copy(sourceJson="broken",payload=appJson.encodeToString(appJson.decodeFromString<LoreBook>(asset.payload).copy(followSessionBudget=null)))
        assertEquals(2,compile(malformed,8192).hits.size)
    }

    @Test fun pngAndStoredCardsExposeAllLoreIncludingDisabledContentWithoutExecutingExtensions() {
        val inspection=CharacterCardInspector.inspect(png(root()))
        val text=inspection.plainText()
        assertTrue(text.contains("世界书 3：背景"));assertTrue(text.contains("丙".repeat(1500)))
        assertTrue(text.contains("关闭的剧透"));assertTrue(text.contains("封存内容"))
        assertTrue(text.contains("角色说明\n第二行"));assertTrue(text.contains("<script>原文，不执行</script>"))
        assertFalse(text.contains("private"));assertTrue(inspection.summary.contains("4 条"))
        assertTrue(inspection.summary.contains("3450 tokens"))
        assertFalse(inspection.sourceJson.contains("api_key"))
        val card=CommunityImporter.decode(root().toByteArray()).characters.single()
        assertEquals(inspection.plainText(),CharacterCardInspector.stored(card).plainText())
        val parsed=appJson.parseToJsonElement(inspection.sourceJson) as JsonObject
        assertEquals(4,((parsed["data"] as JsonObject)["character_book"] as JsonObject)["entries"]!!.jsonArray.size)
    }

    @Test fun disguisedJpegAndPlainPicturesExplainLostMetadataAndNonCardJsonIsRejected() {
        val e=assertThrows(IllegalArgumentException::class.java) {CharacterCardInspector.inspect(byteArrayOf(-1,-40,-1,-32))}
        assertTrue(e.message!!.contains("JPEG"));assertTrue(e.message!!.contains("已导入的角色"))
        assertThrows(IllegalArgumentException::class.java) {CharacterCardInspector.inspect("{\"name\":\"预设\",\"prompts\":[]}".toByteArray())}
        assertThrows(IllegalArgumentException::class.java) {CharacterCardInspector.inspect(png(null))}
        assertThrows(IllegalArgumentException::class.java) {CommunityImporter.decode(byteArrayOf(-1,-40,-1,-32),"card.png")}
    }

    @Test fun manuallyCreatedCardsStillExportExistingReadableSettings() {
        val result=CharacterCardInspector.stored(CharacterCard(name="手动角色",description="现有描述",alternateGreetings="[\"另一开场\"]"))
        assertTrue(result.plainText().contains("现有描述"));assertTrue(result.plainText().contains("另一开场"))
        assertTrue(result.summary.contains("没有保存原始卡片"))
    }

    private fun png(text: String?): ByteArray {
        val out=ByteArrayOutputStream();val stream=DataOutputStream(out)
        stream.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            stream.writeInt(bytes.size);val tag=type.toByteArray(Charsets.US_ASCII);stream.write(tag);stream.write(bytes)
            stream.writeInt(CRC32().apply {update(tag);update(bytes)}.value.toInt())
        }
        if(text!=null) chunk("tEXt","chara\u0000".toByteArray()+Base64.getEncoder().encode(text.toByteArray()))
        chunk("IEND",byteArrayOf());return out.toByteArray()
    }
}
