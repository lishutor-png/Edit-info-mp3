package com.example.mp3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

object SampleMp3Helper {

    /**
     * Creates a valid, playable sample MP3 file in cache directory.
     * Guaranteed to be accepted by Android MediaPlayer, Stagefright, and system players.
     */
    fun createSampleMp3File(context: Context): File {
        val cacheFile = File(context.cacheDir, "sample_lagu_lushedinfo.mp3")
        if (cacheFile.exists() && cacheFile.length() > 5000) {
            return cacheFile
        }

        // Generate sample cover bitmap
        val coverBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(coverBitmap)
        val paint = Paint().apply { isAntiAlias = true }

        // Background
        paint.color = Color.parseColor("#0F172A")
        canvas.drawRect(0f, 0f, 400f, 400f, paint)

        // Circle accent
        paint.color = Color.parseColor("#0D9488")
        canvas.drawCircle(200f, 170f, 90f, paint)

        // Secondary circle
        paint.color = Color.parseColor("#10B981")
        canvas.drawCircle(200f, 170f, 50f, paint)

        // Text
        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("LishEdInfo", 200f, 320f, paint)

        paint.textSize = 18f
        paint.color = Color.parseColor("#94A3B8")
        canvas.drawText("Sample Audio Track", 200f, 355f, paint)

        val coverBytes = ImageUtils.compressBitmapToCoverBytes(coverBitmap, quality = 85, maxDimension = 400)
        coverBitmap.recycle()

        val sampleMetadata = Mp3Metadata(
            title = "Harmoni Petang",
            artist = "Lish Project",
            album = "Akustik Santai Vol. 1",
            genre = "Acoustic",
            year = "2024",
            trackNumber = "1",
            coverImageBytes = coverBytes,
            coverMimeType = "image/jpeg",
            fileName = "sample_lagu_lushedinfo.mp3"
        )

        // Generate MPEG-1 Layer 3 silent frames (128 kbps, 44.1 kHz, stereo)
        // Header: 0xFF, 0xFB, 0x90, 0x64 (417 bytes per frame)
        // 76 frames = ~2 seconds of pristine playable audio
        val frameSize = 417
        val frameCount = 76
        val rawAudio = ByteArray(frameSize * frameCount)

        for (i in 0 until frameCount) {
            val offset = i * frameSize
            rawAudio[offset] = 0xFF.toByte()
            rawAudio[offset + 1] = 0xFB.toByte()
            rawAudio[offset + 2] = 0x90.toByte() // 128kbps, 44.1kHz, no padding
            rawAudio[offset + 3] = 0x64.toByte() // Joint stereo
            // The rest of the 413 bytes remain 0x00 (silent MPEG audio frame data)
        }

        // Write temp raw file
        val tempRaw = File(context.cacheDir, "temp_raw.mp3")
        FileOutputStream(tempRaw).use { it.write(rawAudio) }

        // Export with complete tags using our Mp3Id3Engine
        FileOutputStream(cacheFile).use { fos ->
            Mp3Id3Engine.exportMp3(tempRaw, sampleMetadata, fos)
        }
        tempRaw.delete()

        return cacheFile
    }
}
