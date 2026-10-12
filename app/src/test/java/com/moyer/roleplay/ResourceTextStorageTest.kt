package com.moyer.roleplay

import com.moyer.roleplay.data.*
import org.junit.Assert.*
import org.junit.Test

class ResourceTextStorageTest {
    @Test fun oversizedFieldsRoundTripAndNoPartSplitsEmoji() {
        val text="x".repeat(ResourceTextStorage.CHARS_PER_PART-1)+"🦊"+"中".repeat(200000)
        val original=ResourceAsset(id="big",kind="preset",name="大预设",payload=text,sourceJson=text+text,notes=text)
        val (row,parts)=ResourceTextStorage.split(original)
        assertEquals("",row.payload);assertEquals("",row.sourceJson);assertEquals("",row.notes)
        assertTrue(parts.all { it.text.length <= ResourceTextStorage.CHARS_PER_PART })
        assertTrue(parts.none {it.text.last().isHighSurrogate() || it.text.first().isLowSurrogate()})
        val utf8=parts.map {it.copy(text=it.text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))}
        assertEquals(original,ResourceTextStorage.join(row,utf8.reversed()))
    }
    @Test fun inlineDataStillWorksWithoutAnyParts() {
        val original=ResourceAsset(kind="preset",name="小预设",payload="{}",sourceJson="保留",notes="说明")
        val (row,parts)=ResourceTextStorage.split(original)
        assertTrue(parts.isEmpty());assertEquals(original,ResourceTextStorage.join(row,parts))
    }
}
