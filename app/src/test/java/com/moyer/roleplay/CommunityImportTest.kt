package com.moyer.roleplay

import com.moyer.roleplay.core.*
import com.moyer.roleplay.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class CommunityImportTest {
    private val card="""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"旅人","description":"来自旧城","first_mes":"你好 {{user}}","alternate_greetings":["雨夜"],"system_prompt":"保留角色约定","custom_field":"保留此字段","api_key":"never-retain","character_book":{"entries":[{"keys":["门"],"content":"门后是书库","enabled":true}]},"extensions":{"regex_scripts":[{"scriptName":"称谓","findRegex":"/你/g","replaceString":"旅人","placement":[2],"markdownOnly":true}]}}}"""

    @Test fun ccv2ImportsEmbeddedAssetsAndPreservesUnknownFieldsWithoutCredentials() {
        val pack=CommunityImporter.decode(card.toByteArray())
        assertEquals(1,pack.characters.size);assertEquals(3,pack.resources.size)
        val imported=pack.characters.single(); val config=readConfig(imported.config)
        assertTrue(config.characterPromptId!=null);assertNull(config.presetId)
        assertEquals(setOf("lorebook","regex","preset"),pack.resources.map {it.kind}.toSet())
        assertEquals(pack.resources.map {it.id}.toSet(),config.references())
        assertTrue(imported.sourceJson.contains("custom_field"));assertFalse(imported.sourceJson.contains("never-retain"))
        assertEquals(listOf("雨夜"),appJson.decodeFromString<List<String>>(imported.alternateGreetings))
    }

    @Test fun ccv3WinsOverLegacyPngMetadataAndMissingMetadataIsExplained() {
        val v3=card.replace("chara_card_v2","chara_card_v3").replace("旅人","新旅人")
        val image=png(listOf("tEXt" to text("chara",card),"tEXt" to text("ccv3",v3)))
        assertEquals("新旅人",CommunityImporter.decode(image,"card.png").characters.single().name)
        val error=runCatching {CommunityImporter.decode(png(emptyList()))}.exceptionOrNull()!!
        assertTrue(error.message!!.contains("元数据"))
    }

    @Test fun compressedPngMetadataWorksAndCrcTamperingIsRejected() {
        val encoded=Base64.getEncoder().encode(card.toByteArray())
        val compressed=ByteArrayOutputStream().also {out->DeflaterOutputStream(out).use {it.write(encoded)}}.toByteArray()
        val z=png(listOf("zTXt" to ("chara".toByteArray()+byteArrayOf(0,0)+compressed)))
        assertEquals("旅人",CommunityImporter.decode(z).characters.single().name)
        val international=png(listOf("iTXt" to ("chara".toByteArray()+byteArrayOf(0,1,0,0,0)+compressed)))
        assertEquals("旅人",CommunityImporter.decode(international).characters.single().name)
        val broken=z.copyOf();broken[broken.lastIndex-1]=(broken[broken.lastIndex-1].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {CommunityImporter.decode(broken)}
    }

    @Test fun unsupportedLoreFeaturesAreDisabledInsteadOfSilentlyApproximated() {
        val json="""{"entries":{"0":{"comment":"时效条目","key":["门"],"content":"背景","sticky":2},"1":{"comment":"正常条目","key":["门"],"keysecondary":["雨"],"selective":true,"selectiveLogic":3,"content":"背景","position":4,"depth":1,"role":1}}}"""
        val asset=CommunityImporter.decode(json.toByteArray()).resources.single()
        val entries=appJson.decodeFromString<LoreBook>(asset.payload).entries
        assertFalse(entries.first().enabled);assertTrue(asset.notes.contains("时效条目"))
        assertEquals("and_all",entries[1].logic);assertEquals("depth",entries[1].position);assertEquals("user",entries[1].role)
    }

    @Test fun presetOrdersBlocksSkipsMarkersAndNeverAppliesApiSettings() {
        val json="""{"prompts":[{"identifier":"a","name":"A","content":"先写 A"},{"identifier":"b","name":"B","content":"先写 B"},{"identifier":"marker","marker":true,"content":"skip"},{"identifier":"trigger","content":"unsupported","injection_trigger":["continue"]}],"prompt_order":[{"order":[{"identifier":"b","enabled":true},{"identifier":"a","enabled":false},{"identifier":"trigger","enabled":true}]}],"custom_url":"https://untrusted.example","api_key":"hidden-key","temperature":2}"""
        val asset=CommunityImporter.decode(json.toByteArray()).resources.single()
        val blocks=appJson.decodeFromString<StylePreset>(asset.payload).blocks
        assertEquals(listOf("B","A","trigger"),blocks.map {it.name})
        assertTrue(blocks.first().enabled);assertFalse(blocks[1].enabled);assertTrue(blocks[2].enabled);assertEquals(listOf("continue"),blocks[2].triggers)
        assertFalse(asset.sourceJson.contains("hidden-key"));assertTrue(asset.notes.contains("不会随预设生效"))
    }

    @Test fun regexScopeAndUnsupportedFlagsProduceReviewableImport() {
        val json="""[{"scriptName":"显示","findRegex":"/a/g","replaceString":"b","placement":[2],"markdownOnly":true},{"scriptName":"不支持","findRegex":"/a/u","replaceString":"c","placement":[2]}]"""
        val pack=CommunityImporter.decode(json.toByteArray());val rules=appJson.decodeFromString<RegexRules>(pack.resources.single().payload).rules
        assertTrue(rules[0].display);assertFalse(rules[0].prompt);assertFalse(rules[1].enabled);assertFalse(pack.warnings.isEmpty())
    }

    @Test fun macroFindIsValidatedAfterSubstitutionAndUnsupportedLoreRegexIsReported() {
        val script="""{"scriptName":"宏","findRegex":"/{{char}}/g","replaceString":"{{user}}","placement":[2],"substituteRegex":2}"""
        val asset=CommunityImporter.decode(script.toByteArray()).resources.single()
        val rule=appJson.decodeFromString<RegexRules>(asset.payload).rules.single()
        assertTrue(rule.enabled)
        assertEquals("玩家",RegexEngine.apply("角色",listOf(rule),"assistant",true,0,"角色","玩家").text)
        val lore="""{"entries":[{"keys":["/(?=a)/"],"content":"内容"}]}"""
        val book=CommunityImporter.decode(lore.toByteArray()).resources.single()
        assertFalse(appJson.decodeFromString<LoreBook>(book.payload).entries.single().enabled)
        assertTrue(book.notes.contains("正则"))
    }

    private fun text(key: String,value: String)=key.toByteArray()+byteArrayOf(0)+Base64.getEncoder().encode(value.toByteArray())
    private fun png(chunks: List<Pair<String,ByteArray>>): ByteArray {
        val output=ByteArrayOutputStream();output.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        (chunks+listOf("IEND" to byteArrayOf())).forEach { (type,data)->
            val bytes=type.toByteArray()+data;DataOutputStream(output).apply {writeInt(data.size);write(bytes);writeInt(CRC32().apply {update(bytes)}.value.toInt())}
        }
        return output.toByteArray()
    }
}
