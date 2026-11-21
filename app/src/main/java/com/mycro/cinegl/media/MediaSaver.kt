package com.mycro.cinegl.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写入系统相册（DCIM/CineGL）。Android 10+ 用 MediaStore 不需要存储权限。 */
object MediaSaver {

    class PendingVideo(val uri: Uri, val fd: ParcelFileDescriptor)

    data class PhotoMeta(
        val iso: Int?,
        val exposureNs: Long?,
        val focalLength: Float?,
        val aperture: Float?,
    )

    private val relativePath = "${Environment.DIRECTORY_DCIM}/CineGL"

    private fun fileName(ext: String): String =
        "CineGL_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.$ext"

    /** 先插一条「待定」记录（IS_PENDING=1，相册里看不到），录完再公开。 */
    fun createVideo(context: Context): PendingVideo? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName("mp4"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        val fd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (fd == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return PendingVideo(uri, fd)
    }

    fun publish(context: Context, uri: Uri) {
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** JPEG + EXIF：先在缓存目录写好（ExifInterface 改文件最省心），再整体拷进相册。 */
    fun savePhoto(context: Context, bitmap: Bitmap, meta: PhotoMeta): Uri? {
        val tmp = File(context.cacheDir, "capture_${System.nanoTime()}.jpg")
        try {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(tmp.absolutePath).apply {
                val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                setAttribute(ExifInterface.TAG_DATETIME, now)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                setAttribute(ExifInterface.TAG_SOFTWARE, "CineGL")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                meta.iso?.let { setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
                meta.exposureNs?.let { setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString()) }
                meta.focalLength?.let { setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000") }
                meta.aperture?.let { setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
                saveAttributes()
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName("jpg"))
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (!copied) {
                resolver.delete(uri, null, null)
                return null
            }
            publish(context, uri)
            return uri
        } finally {
            tmp.delete()
        }
    }
}
