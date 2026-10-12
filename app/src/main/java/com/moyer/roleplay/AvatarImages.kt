package com.moyer.roleplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal object AvatarImages {
    private const val MAX_BYTES = 16 * 1024 * 1024
    // Keys are short digests, so repeated session avatars do not retain duplicate Base64 strings.
    private val thumbnails = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun fromGallery(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = (context.contentResolver.openInputStream(uri) ?: error("图片无法读取")).use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "图片超过 16 MB，请选择较小的图片" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        val bitmap = decode(bytes, 512) ?: throw IllegalArgumentException("图片格式无法读取，请选择 JPG、PNG 或设备支持的图片")
        var upright = bitmap
        try {
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            if (!matrix.isIdentity) upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            ByteArrayOutputStream().use { output ->
                require(upright.compress(Bitmap.CompressFormat.PNG, 100, output)) { "头像无法保存" }
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        }
    }

    fun decode(encoded: String): Bitmap? = runCatching {
        if (encoded.isBlank() || encoded.length > 4 * 1024 * 1024) null
        else {
            val key = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)
            synchronized(thumbnails) {
                thumbnails.get(key)?.takeUnless { it.isRecycled }
                    ?: decode(Base64.decode(encoded, Base64.DEFAULT), 128)?.also { thumbnails.put(key, it) }
            }
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "图片尺寸无效或过大" }
        var sample = 1
        while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
