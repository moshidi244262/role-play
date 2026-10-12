package com.moyer.roleplay.core

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

object PngCardCodec {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    fun isPng(bytes: ByteArray) = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(signature)
    fun extract(bytes: ByteArray): String {
        require(isPng(bytes)) { "不是有效 PNG 文件" }
        require(bytes.size <= BackupCodec.MAX_BYTES) { "PNG 角色卡超过 16 MB" }
        fun intAt(pos: Int): Long = (0..3).fold(0L) { result, n -> (result shl 8) or (bytes[pos + n].toLong() and 255) }
        var offset = 8; var chara: String? = null; var v3: String? = null; var ended = false
        while (offset + 12 <= bytes.size) {
            val size = intAt(offset)
            require(size <= bytes.size - offset - 12) { "PNG 数据块损坏" }
            val length = size.toInt()
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
            val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
            require(crc == intAt(offset + 8 + length)) { "PNG 校验失败，文件可能已损坏" }
            if (type in setOf("tEXt", "zTXt", "iTXt")) {
                val keyEnd = data.indexOf(0)
                if (keyEnd > 0) {
                    val key = data.copyOfRange(0, keyEnd).toString(Charsets.US_ASCII)
                    if (key in setOf("chara", "ccv3")) {
                        val encoded = when (type) {
                            "tEXt" -> data.copyOfRange(keyEnd + 1, data.size)
                            "zTXt" -> {
                                require(keyEnd + 2 <= data.size && data[keyEnd + 1] == 0.toByte()) { "PNG 文本压缩格式不支持" }
                                inflate(data.copyOfRange(keyEnd + 2, data.size))
                            }
                            else -> {
                                var start = keyEnd + 1
                                require(start + 2 <= data.size) { "PNG 文本数据损坏" }
                                val compressed = data[start++] != 0.toByte()
                                require(data[start++] == 0.toByte()) { "PNG 文本压缩格式不支持" }
                                repeat(2) {
                                    while (start < data.size && data[start] != 0.toByte()) start++
                                    require(start < data.size) { "PNG 文本数据损坏" }; start++
                                }
                                data.copyOfRange(start, data.size).let { if (compressed) inflate(it) else it }
                            }
                        }
                        val decoded = try { Base64.getMimeDecoder().decode(encoded) }
                        catch (_: Exception) { throw IllegalArgumentException("PNG 中的角色资料编码无效") }
                        require(decoded.size <= 4 * 1024 * 1024) { "角色资料超过 4 MB" }
                        if (key == "ccv3") v3 = decoded.toString(Charsets.UTF_8) else chara = decoded.toString(Charsets.UTF_8)
                    }
                }
            }
            offset += length + 12
            if (type == "IEND") { ended = true; break }
        }
        require(ended) { "PNG 文件不完整" }
        return v3 ?: chara ?: throw IllegalArgumentException("图片没有角色卡资料；聊天平台可能清除了 PNG 元数据，请重新下载原始文件")
    }
    private fun inflate(bytes: ByteArray): ByteArray = InflaterInputStream(ByteArrayInputStream(bytes)).use {
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) {
            val n = it.read(chunk); if (n < 0) break
            require(out.size() + n <= 6 * 1024 * 1024) { "PNG 压缩文本过大" }
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    }
}
