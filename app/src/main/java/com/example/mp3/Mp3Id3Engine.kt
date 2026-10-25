package com.example.mp3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * High-precision, zero-corruption MP3 ID3 engine.
 * 
 * Strict specifications:
 * 1. Generates 100% compliant ID3v2.3 headers with syncsafe sizes and standard big-endian frame lengths.
 * 2. Uses UTF-16 with BOM for universal Unicode support (Android Stagefright / native extractor).
 * 3. Uses standard APIC (Front Cover, 0x03) with image/jpeg or image/png.
 * 4. Extracts and streams RAW MPEG audio frames byte-for-byte, ensuring zero audio distortion or truncation.
 * 5. Appends compliant 128-byte ID3v1.1 trailer for legacy music players.
 */
object Mp3Id3Engine {

    data class AudioOffsets(
        val audioStartOffset: Long,
        val audioEndOffset: Long,
        val existingId3v2Size: Long,
        val hasId3v1: Boolean
    )

    /**
     * Inspect an MP3 file to locate where the actual MPEG audio frames begin and end.
     */
    fun findAudioStreamOffsets(file: File): AudioOffsets {
        val fileLength = file.length()
        if (fileLength < 10) {
            return AudioOffsets(0L, fileLength, 0L, false)
        }

        var audioStart = 0L
        var id3v2Size = 0L

        FileInputStream(file).use { fis ->
            val header = ByteArray(10)
            val read = fis.read(header)
            if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val flags = header[5].toInt() and 0xFF
                val s0 = header[6].toInt() and 0x7F
                val s1 = header[7].toInt() and 0x7F
                val s2 = header[8].toInt() and 0x7F
                val s3 = header[9].toInt() and 0x7F
                val tagPayloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                id3v2Size = 10L + tagPayloadSize
                if ((flags and 0x10) != 0) {
                    // ID3v2.4 footer present
                    id3v2Size += 10L
                }
                audioStart = id3v2Size
            }
        }

        if (audioStart > fileLength) {
            audioStart = 0L
        }

        var audioEnd = fileLength
        var hasId3v1 = false

        if (fileLength >= 128) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fileLength - 128)
                val tag = ByteArray(3)
                raf.readFully(tag)
                if (tag[0] == 'T'.code.toByte() && tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()) {
                    hasId3v1 = true
                    audioEnd = fileLength - 128
                }
            }
        }

        if (audioEnd < audioStart) {
            audioEnd = fileLength
        }

        return AudioOffsets(audioStart, audioEnd, id3v2Size, hasId3v1)
    }

    /**
     * Extract existing metadata using MediaMetadataRetriever and ID3 parsing.
     */
    fun extractMetadata(context: Context, file: File): Mp3Metadata {
        var title = ""
        var artist = ""
        var album = ""
        var genre = ""
        var year = ""
        var trackNumber = ""
        var durationMs = 0L
        var coverBytes: ByteArray? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: ""
            trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: ""
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            coverBytes = retriever.embeddedPicture
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }

        // Fallback for file name if title is empty
        val cleanName = file.nameWithoutExtension.ifBlank { "Lagu" }
        if (title.isBlank()) {
            title = cleanName
        }

        val bitmap = ImageUtils.bytesToBitmap(coverBytes)

        return Mp3Metadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            coverImageBytes = coverBytes,
            coverMimeType = if (coverBytes != null && isPng(coverBytes)) "image/png" else "image/jpeg",
            durationMs = durationMs,
            fileSize = file.length(),
            fileName = file.name,
            coverBitmap = bitmap
        )
    }

    private fun isPng(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
    }

    /**
     * Build an ID3v2.3 tag byte array containing all requested tags and album art.
     */
    fun buildId3v23Tag(metadata: Mp3Metadata): ByteArray {
        val framesOut = ByteArrayOutputStream()

        // Write Text Frames (TIT2, TPE1, TALB, TYER, TCON, TRCK)
        if (metadata.title.isNotBlank()) {
            writeTextFrame(framesOut, "TIT2", metadata.title.trim())
        }
        if (metadata.artist.isNotBlank()) {
            writeTextFrame(framesOut, "TPE1", metadata.artist.trim())
        }
        if (metadata.album.isNotBlank()) {
            writeTextFrame(framesOut, "TALB", metadata.album.trim())
        }
        if (metadata.year.isNotBlank()) {
            writeTextFrame(framesOut, "TYER", metadata.year.trim())
        }
        if (metadata.genre.isNotBlank()) {
            writeTextFrame(framesOut, "TCON", metadata.genre.trim())
        }
        if (metadata.trackNumber.isNotBlank()) {
            writeTextFrame(framesOut, "TRCK", metadata.trackNumber.trim())
        }

        // Write APIC (Cover Art) Frame
        val cover = metadata.coverImageBytes
        if (cover != null && cover.isNotEmpty()) {
            writeApicFrame(framesOut, cover, metadata.coverMimeType)
        }

        val framesBytes = framesOut.toByteArray()
        val framesSize = framesBytes.size

        // Build 10-byte ID3v2.3 header
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 0x03 // ID3v2.3
        header[4] = 0x00 // Revision 0
        header[5] = 0x00 // Flags: No unsync, no extended header, no experimental

        // Frame size is encoded as syncsafe integer in the header
        header[6] = ((framesSize shr 21) and 0x7F).toByte()
        header[7] = ((framesSize shr 14) and 0x7F).toByte()
        header[8] = ((framesSize shr 7) and 0x7F).toByte()
        header[9] = (framesSize and 0x7F).toByte()

        val fullTag = ByteArray(10 + framesSize)
        System.arraycopy(header, 0, fullTag, 0, 10)
        System.arraycopy(framesBytes, 0, fullTag, 10, framesSize)
        return fullTag
    }

    /**
     * Write an ID3v2.3 text frame with UTF-16 BOM encoding.
     */
    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val payloadOut = ByteArrayOutputStream()
        // Encoding: 0x01 = Unicode UTF-16 with BOM
        payloadOut.write(0x01)
        // Little Endian BOM
        payloadOut.write(0xFF)
        payloadOut.write(0xFE)
        // UTF-16LE text bytes
        payloadOut.write(text.toByteArray(StandardCharsets.UTF_16LE))

        val payload = payloadOut.toByteArray()

        // 4-byte Frame ID
        out.write(frameId.toByteArray(StandardCharsets.US_ASCII))

        // 4-byte Frame Size (standard big-endian 32-bit int in ID3v2.3)
        val size = payload.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)

        // 2-byte Flags (0x00, 0x00)
        out.write(0x00)
        out.write(0x00)

        // Payload
        out.write(payload)
    }

    /**
     * Write an ID3v2.3 APIC (Attached picture) frame.
     */
    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        val payloadOut = ByteArrayOutputStream()
        // Text encoding: 0x00 = ISO-8859-1 for MIME and description
        payloadOut.write(0x00)

        // MIME type + 0x00 null terminator
        val mime = if (mimeType.isBlank()) "image/jpeg" else mimeType
        payloadOut.write(mime.toByteArray(StandardCharsets.US_ASCII))
        payloadOut.write(0x00)

        // Picture type: 0x03 = Cover (front)
        payloadOut.write(0x03)

        // Description + 0x00 null terminator (empty description)
        payloadOut.write(0x00)

        // Binary picture data
        payloadOut.write(imageBytes)

        val payload = payloadOut.toByteArray()

        // 4-byte Frame ID "APIC"
        out.write("APIC".toByteArray(StandardCharsets.US_ASCII))

        // 4-byte Frame Size (standard big-endian int in ID3v2.3)
        val size = payload.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)

        // 2-byte Flags (0x00, 0x00)
        out.write(0x00)
        out.write(0x00)

        // Payload
        out.write(payload)
    }

    /**
     * Build standard 128-byte ID3v1.1 trailer.
     */
    fun buildId3v1Tag(metadata: Mp3Metadata): ByteArray {
        val tag = ByteArray(128)
        // 0..2: "TAG"
        tag[0] = 'T'.code.toByte()
        tag[1] = 'A'.code.toByte()
        tag[2] = 'G'.code.toByte()

        // 3..32: Title (30 bytes)
        writeIsoString(tag, 3, 30, metadata.title)
        // 33..62: Artist (30 bytes)
        writeIsoString(tag, 33, 30, metadata.artist)
        // 63..92: Album (30 bytes)
        writeIsoString(tag, 63, 30, metadata.album)
        // 93..96: Year (4 bytes)
        writeIsoString(tag, 93, 4, metadata.year)
        // 97..124: Comment (28 bytes)
        writeIsoString(tag, 97, 28, "LishEdInfo")
        // 125: 0x00 (Track marker)
        tag[125] = 0x00
        // 126: Track number
        tag[126] = (metadata.trackNumber.toIntOrNull() ?: 0).coerceIn(0, 255).toByte()
        // 127: Genre (255 = None)
        tag[127] = 255.toByte()

        return tag
    }

    private fun writeIsoString(dest: ByteArray, offset: Int, maxLen: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        val copyLen = bytes.size.coerceAtMost(maxLen)
        System.arraycopy(bytes, 0, dest, offset, copyLen)
    }

    /**
     * Export the new MP3 file with updated tags while preserving exact raw audio stream.
     */
    fun exportMp3(sourceFile: File, newMetadata: Mp3Metadata, outputStream: OutputStream) {
        val offsets = findAudioStreamOffsets(sourceFile)

        // 1. Write the new ID3v2.3 tag
        val id3v2Bytes = buildId3v23Tag(newMetadata)
        outputStream.write(id3v2Bytes)

        // 2. Stream untouched raw audio stream
        FileInputStream(sourceFile).use { fis ->
            if (offsets.audioStartOffset > 0) {
                var skipped = 0L
                while (skipped < offsets.audioStartOffset) {
                    val n = fis.skip(offsets.audioStartOffset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
            }

            var bytesRemaining = offsets.audioEndOffset - offsets.audioStartOffset
            val buffer = ByteArray(65536)
            while (bytesRemaining > 0) {
                val toRead = bytesRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                val read = fis.read(buffer, 0, toRead)
                if (read <= 0) break
                outputStream.write(buffer, 0, read)
                bytesRemaining -= read
            }
        }

        // 3. Write ID3v1 tag
        val id3v1Bytes = buildId3v1Tag(newMetadata)
        outputStream.write(id3v1Bytes)
        outputStream.flush()
    }
}
