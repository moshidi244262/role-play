package com.moyer.roleplay.data

import androidx.room.*

@Entity(tableName = "resource_text_parts", primaryKeys = ["resourceId", "field", "part"],
    foreignKeys = [ForeignKey(entity = ResourceAsset::class, parentColumns = ["id"],
        childColumns = ["resourceId"], onDelete = ForeignKey.CASCADE)], indices = [Index("resourceId")])
data class ResourceTextPart(val resourceId: String, val field: String, val part: Int, val text: String)

object ResourceTextStorage {
    const val CHARS_PER_PART = 64 * 1024

    /** Keep every SQLite cursor row small, including uncompressible data. */
    fun split(resource: ResourceAsset): Pair<ResourceAsset, List<ResourceTextPart>> {
        val parts = mutableListOf<ResourceTextPart>()
        fun store(field: String, text: String): String {
            if(text.length <= CHARS_PER_PART) return text
            var start=0
            var index=0
            while(start < text.length) {
                var end=(start+CHARS_PER_PART).coerceAtMost(text.length)
                // Never split an emoji's UTF-16 surrogate pair before SQLite encodes it as UTF-8.
                if(end < text.length && text[end-1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                parts += ResourceTextPart(resource.id,field,index++,text.substring(start,end))
                start=end
            }
            return ""
        }
        val row=resource.copy(payload=store("payload",resource.payload), sourceJson=store("sourceJson",resource.sourceJson),
            notes=store("notes",resource.notes))
        return row to parts
    }

    fun join(row: ResourceAsset, parts: List<ResourceTextPart>): ResourceAsset {
        val fields=parts.groupBy { it.field }
        fun text(field: String,fallback: String) = fields[field]?.sortedBy { it.part }?.joinToString("") { it.text } ?: fallback
        return row.copy(payload=text("payload",row.payload),sourceJson=text("sourceJson",row.sourceJson),notes=text("notes",row.notes))
    }
}
