package com.rudnam.samfran.core.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.rudnam.samfran.core.common.Dispatcher
import com.rudnam.samfran.core.common.SamFranDispatchers
import com.rudnam.samfran.core.model.Photo
import com.rudnam.samfran.core.network.SamFranNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A photo picked on the phone, ready to send: [open] reads the original as
 * it was taken, never a smaller copy; [thumb] is a small JPEG of it for
 * the strips and cards (internal/restaurant/photo.go).
 */
class PickedPhoto(
    val name: String,
    val contentType: String,
    /** Bytes; -1 when not known. */
    val size: Long,
    val width: Int,
    val height: Int,
    val thumb: ByteArray,
    val open: () -> InputStream,
)

/** A picked photo the server couldn't show (not a picture this app can open). */
class UnreadablePhotoException : Exception("that photo can't be opened")

/** Reads photos picked on the phone. */
interface Photos {
    /** The photo at [uri] (from the photo picker), or [UnreadablePhotoException]. */
    suspend fun read(uri: String): PickedPhoto

    /**
     * Saves a photo's original, exactly as it was taken, to the phone's
     * Pictures/SamFran, where the gallery shows it. [Outcome.Refused] with
     * [COULD_NOT_SAVE] when the phone wouldn't keep it.
     */
    suspend fun save(photo: Photo): Outcome<Unit>

    companion object {
        /** The words for a photo the phone wouldn't save; the screen translates them. */
        const val COULD_NOT_SAVE = "the photo couldn't be saved on this phone"
    }
}

/** The phone wouldn't keep a downloaded photo (no room, no permission). */
private class CouldNotSaveException(cause: Throwable?) : Exception(cause)

@Singleton
internal class AndroidPhotos @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(SamFranDispatchers.IO) private val io: CoroutineDispatcher,
    private val network: SamFranNetwork,
) : Photos {
    override suspend fun save(photo: Photo): Outcome<Unit> = try {
        outcome(io) {
            network.downloadPhoto(photo.url) { contentType, input ->
                val mime = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val resolver = context.contentResolver
                val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "samfran-${photo.id}.$ext")
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    if (pending) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SamFran")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val collection = if (pending) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = try {
                    resolver.insert(collection, values) ?: throw CouldNotSaveException(null)
                } catch (e: SecurityException) {
                    throw CouldNotSaveException(e)
                } catch (e: IllegalArgumentException) {
                    throw CouldNotSaveException(e)
                }
                try {
                    // Read from the server as it's written: a 40 MB photo is never all in memory.
                    (resolver.openOutputStream(uri) ?: throw CouldNotSaveException(null)).use { input.copyTo(it) }
                    if (pending) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Throwable) {
                    resolver.delete(uri, null, null) // never half a photo in the gallery
                    throw e
                }
            }
        }
    } catch (e: CouldNotSaveException) {
        Outcome.Refused(Photos.COULD_NOT_SAVE)
    }

    override suspend fun read(uri: String): PickedPhoto = withContext(io) {
        val u = Uri.parse(uri)
        val resolver = context.contentResolver
        val type = resolver.getType(u)?.lowercase().orEmpty()
        var name = "photo"
        var size = -1L
        resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val (bitmap, width, height) = decode(u, THUMB) ?: throw UnreadablePhotoException()
        val thumb = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
        bitmap.recycle()
        if (type in SENT_AS_IS) {
            PickedPhoto(name, type, size, width, height, thumb) { resolver.openInputStream(u) ?: throw UnreadablePhotoException() }
        } else {
            // HEIC and the like, which browsers can't show: the whole picture, at
            // every pixel, as a JPEG at the highest quality.
            val full = decode(u, 0)?.first ?: throw UnreadablePhotoException()
            val file = File.createTempFile("photo", ".jpg", context.cacheDir)
            file.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            full.recycle()
            file.deleteOnExit()
            PickedPhoto(name.substringBeforeLast('.') + ".jpg", "image/jpeg", file.length(), width, height, thumb) { file.inputStream() }
        }
    }

    /**
     * The picture, turned the way it was taken, its longest side at most
     * [longest] (0: every pixel), with the original's width and height.
     */
    private fun decode(u: Uri, longest: Int): Triple<Bitmap, Int, Int>? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            var w = 0
            var h = 0
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, u)) { decoder, info, _ ->
                w = info.size.width
                h = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (longest > 0 && max(w, h) > longest) {
                    val scale = longest.toFloat() / max(w, h)
                    decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                }
            }
            Triple(bitmap, w, h)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            if (longest > 0) while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
            val bitmap = context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            bitmap?.let { Triple(it, bounds.outWidth, bounds.outHeight) }
        }
    }.getOrNull()

    private companion object {
        const val THUMB = 480

        /** What the server keeps as it is (internal/restaurant/photo.go). */
        val SENT_AS_IS = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif")
    }
}
