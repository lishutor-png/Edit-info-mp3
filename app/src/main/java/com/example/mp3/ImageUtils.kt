package com.example.mp3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /**
     * Decode Uri to Bitmap, downsampling if needed to avoid out of memory.
     */
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int = 1000, reqHeight: Int = 1000): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Compress bitmap into optimized JPEG byte array for ID3 APIC frame.
     */
    fun compressBitmapToCoverBytes(bitmap: Bitmap, quality: Int = 85, maxDimension: Int = 1000): ByteArray {
        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
            val newW = max(1, (bitmap.width * ratio).toInt())
            val newH = max(1, (bitmap.height * ratio).toInt())
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled != bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        return out.toByteArray()
    }

    fun bytesToBitmap(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }
}
