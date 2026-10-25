package com.example.mp3

import android.graphics.Bitmap

/**
 * Data model for MP3 song information.
 */
data class Mp3Metadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val coverImageBytes: ByteArray? = null,
    val coverMimeType: String = "image/jpeg",
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val fileName: String = "",
    val coverBitmap: Bitmap? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Mp3Metadata
        if (title != other.title) return false
        if (artist != other.artist) return false
        if (album != other.album) return false
        if (genre != other.genre) return false
        if (year != other.year) return false
        if (trackNumber != other.trackNumber) return false
        if (coverImageBytes != null) {
            if (other.coverImageBytes == null) return false
            if (!coverImageBytes.contentEquals(other.coverImageBytes)) return false
        } else if (other.coverImageBytes != null) return false
        if (coverMimeType != other.coverMimeType) return false
        if (durationMs != other.durationMs) return false
        if (fileSize != other.fileSize) return false
        if (fileName != other.fileName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + year.hashCode()
        result = 31 * result + trackNumber.hashCode()
        result = 31 * result + (coverImageBytes?.contentHashCode() ?: 0)
        result = 31 * result + coverMimeType.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
