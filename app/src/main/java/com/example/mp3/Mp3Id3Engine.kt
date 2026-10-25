package com.example.mp3

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * High-precision, zero-corruption MP3 ID3 engine.
 * 
 * Guarantees:
 * 1. Accurately locates the true start of MPEG audio frames by searching for valid frame sync (0xFF 0xEx).
 * 2. Copies raw MPEG audio bitstream byte-for-byte using RandomAccessFile seeking without re-encoding.
 * 3. Builds standard ID3v2.3 tag with UTF-16 BOM text encoding and APIC front cover.
 * 4. Parses ID3v2 and ID3v1 tags directly from file bytes in addition to MediaMetadataRetriever.
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

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
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

            if (audioStart > fileLength) {
                audioStart = 0L
            }

            // Verify and scan for the first MPEG audio frame sync word starting at audioStart
            // Discards any padding bytes (0x00) or garbage between the tag and audio stream.
            val scanLen = minOf(65536, (fileLength - audioStart).toInt())
            if (scanLen > 4) {
                raf.seek(audioStart)
                val scanBuf = ByteArray(scanLen)
                val read = raf.read(scanBuf)
                if (read > 4) {
                    for (i in 0 until read - 3) {
                        val b0 = scanBuf[i].toInt() and 0xFF
                        val b1 = scanBuf[i + 1].toInt() and 0xFF
                        // Frame sync: 11 consecutive 1s (0xFF, followed by 111x xxxx)
                        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                            val layer = (b1 shr 1) and 0x03
                            if (layer != 0) { // Valid MPEG layer (Layer I, II, or III)
                                val b2 = scanBuf[i + 2].toInt() and 0xFF
                                val bitrateIdx = (b2 shr 4) and 0x0F
                                val sampleRateIdx = (b2 shr 2) and 0x03
                                if (bitrateIdx != 0x0F && sampleRateIdx != 0x03) {
                                    audioStart += i
                                    break
                                }
                            }
                        }
                    }
                }
            }

            var audioEnd = fileLength
            var hasId3v1 = false

            // Check for ID3v1 trailer (128 bytes)
            if (fileLength >= 128) {
                raf.seek(fileLength - 128)
                val tag = ByteArray(3)
                raf.readFully(tag)
                if (tag[0] == 'T'.code.toByte() && tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()) {
                    hasId3v1 = true
                    audioEnd = fileLength - 128
                }
            }

            // Check for APEv2 trailer before ID3v1 or EOF
            if (audioEnd >= 32) {
                raf.seek(audioEnd - 32)
                val apeHeader = ByteArray(8)
                raf.readFully(apeHeader)
                if (String(apeHeader, StandardCharsets.US_ASCII) == "APETAGEX") {
                    val sizeBuf = ByteArray(4)
                    raf.seek(audioEnd - 32 + 12)
                    raf.readFully(sizeBuf)
                    val apeSize = (sizeBuf[0].toLong() and 0xFF) or
                            ((sizeBuf[1].toLong() and 0xFF) shl 8) or
                            ((sizeBuf[2].toLong() and 0xFF) shl 16) or
                            ((sizeBuf[3].toLong() and 0xFF) shl 24)
                    if (apeSize in 32..audioEnd) {
                        audioEnd -= apeSize
                    }
                }
            }

            if (audioEnd <= audioStart) {
                audioEnd = fileLength
            }

            return AudioOffsets(audioStart, audioEnd, id3v2Size, hasId3v1)
        }
    }

    /**
     * Extract existing metadata by combining direct ID3 parsing and MediaMetadataRetriever.
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

        // 1. Direct ID3 Parser (parses ID3v2 tags directly from binary data)
        try {
            parseDirectId3(file)?.let { direct ->
                if (direct.title.isNotBlank()) title = direct.title
                if (direct.artist.isNotBlank()) artist = direct.artist
                if (direct.album.isNotBlank()) album = direct.album
                if (direct.genre.isNotBlank()) genre = direct.genre
                if (direct.year.isNotBlank()) year = direct.year
                if (direct.trackNumber.isNotBlank()) trackNumber = direct.trackNumber
                if (direct.coverImageBytes != null && direct.coverImageBytes.isNotEmpty()) {
                    coverBytes = direct.coverImageBytes
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. MediaMetadataRetriever fallback/supplement
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (title.isBlank()) {
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            }
            if (artist.isBlank()) {
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            }
            if (album.isBlank()) {
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            }
            if (genre.isBlank()) {
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            }
            if (year.isBlank()) {
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: ""
            }
            if (trackNumber.isBlank()) {
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: ""
            }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (coverBytes == null) {
                coverBytes = retriever.embeddedPicture
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }

        // 3. Fallback for title if empty
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
     * Direct parser for ID3v2 tags (v2.3 and v2.4).
     */
    private fun parseDirectId3(file: File): Mp3Metadata? {
        if (!file.exists() || file.length() < 10) return null

        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return parseId3v1Only(raf, file.length())
            }

            val version = header[3].toInt() and 0xFF
            val s0 = header[6].toInt() and 0x7F
            val s1 = header[7].toInt() and 0x7F
            val s2 = header[8].toInt() and 0x7F
            val s3 = header[9].toInt() and 0x7F
            val tagSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3

            if (tagSize <= 0 || tagSize > file.length() - 10) return null

            val tagBytes = ByteArray(tagSize)
            raf.readFully(tagBytes)

            var offset = 0
            var title = ""
            var artist = ""
            var album = ""
            var genre = ""
            var year = ""
            var track = ""
            var coverBytes: ByteArray? = null

            while (offset + 10 <= tagSize) {
                // If we hit padding (0x00), stop parsing frames
                if (tagBytes[offset] == 0.toByte()) break

                val frameId = String(tagBytes, offset, 4, StandardCharsets.US_ASCII)
                val frameSize = if (version == 4) {
                    // ID3v2.4 uses syncsafe integer for frame size
                    val f0 = tagBytes[offset + 4].toInt() and 0x7F
                    val f1 = tagBytes[offset + 5].toInt() and 0x7F
                    val f2 = tagBytes[offset + 6].toInt() and 0x7F
                    val f3 = tagBytes[offset + 7].toInt() and 0x7F
                    (f0 shl 21) or (f1 shl 14) or (f2 shl 7) or f3
                } else {
                    // ID3v2.3 uses standard big-endian 32-bit int
                    val b0 = tagBytes[offset + 4].toInt() and 0xFF
                    val b1 = tagBytes[offset + 5].toInt() and 0xFF
                    val b2 = tagBytes[offset + 6].toInt() and 0xFF
                    val b3 = tagBytes[offset + 7].toInt() and 0xFF
                    (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                }

                offset += 10
                if (frameSize <= 0 || offset + frameSize > tagSize) break

                when (frameId) {
                    "TIT2" -> title = decodeTextPayload(tagBytes, offset, frameSize)
                    "TPE1" -> artist = decodeTextPayload(tagBytes, offset, frameSize)
                    "TALB" -> album = decodeTextPayload(tagBytes, offset, frameSize)
                    "TYER", "TDRC" -> year = decodeTextPayload(tagBytes, offset, frameSize)
                    "TCON" -> genre = decodeTextPayload(tagBytes, offset, frameSize)
                    "TRCK" -> track = decodeTextPayload(tagBytes, offset, frameSize)
                    "APIC" -> {
                        coverBytes = extractApicPictureData(tagBytes, offset, frameSize)
                    }
                }
                offset += frameSize
            }

            return Mp3Metadata(
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                year = year,
                trackNumber = track,
                coverImageBytes = coverBytes
            )
        }
    }

    private fun decodeTextPayload(data: ByteArray, offset: Int, length: Int): String {
        if (length <= 1) return ""
        val encoding = data[offset].toInt() and 0xFF
        val textBytes = data.copyOfRange(offset + 1, offset + length)

        return try {
            when (encoding) {
                0 -> String(textBytes, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
                1 -> {
                    // UTF-16 with BOM
                    String(textBytes, StandardCharsets.UTF_16).trimEnd('\u0000', ' ')
                }
                2 -> String(textBytes, StandardCharsets.UTF_16BE).trimEnd('\u0000', ' ')
                3 -> String(textBytes, StandardCharsets.UTF_8).trimEnd('\u0000', ' ')
                else -> String(textBytes, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractApicPictureData(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length <= 4) return null
        var ptr = offset
        val encoding = data[ptr].toInt() and 0xFF
        ptr++

        // Read MIME type (null-terminated string)
        val mimeStart = ptr
        while (ptr < offset + length && data[ptr] != 0.toByte()) {
            ptr++
        }
        ptr++ // skip null

        if (ptr >= offset + length) return null
        val pictureType = data[ptr].toInt() and 0xFF
        ptr++

        // Read description (null-terminated string according to encoding)
        if (encoding == 1 || encoding == 2) {
            // UTF-16: 2 null bytes (0x00, 0x00)
            while (ptr + 1 < offset + length && !(data[ptr] == 0.toByte() && data[ptr + 1] == 0.toByte())) {
                ptr += 2
            }
            ptr += 2
        } else {
            // ISO-8859-1 or UTF-8: 1 null byte
            while (ptr < offset + length && data[ptr] != 0.toByte()) {
                ptr++
            }
            ptr++
        }

        if (ptr >= offset + length) return null
        val picLength = (offset + length) - ptr
        if (picLength <= 0) return null

        return data.copyOfRange(ptr, ptr + picLength)
    }

    private fun parseId3v1Only(raf: RandomAccessFile, fileLength: Long): Mp3Metadata? {
        if (fileLength < 128) return null
        raf.seek(fileLength - 128)
        val tag = ByteArray(128)
        raf.readFully(tag)
        if (tag[0] == 'T'.code.toByte() && tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()) {
            val title = String(tag, 3, 30, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val artist = String(tag, 33, 30, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val album = String(tag, 63, 30, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val year = String(tag, 93, 4, StandardCharsets.ISO_8859_1).trimEnd('\u0000', ' ')
            val track = if (tag[125] == 0.toByte() && tag[126] != 0.toByte()) {
                (tag[126].toInt() and 0xFF).toString()
            } else ""

            return Mp3Metadata(
                title = title,
                artist = artist,
                album = album,
                genre = "",
                year = year,
                trackNumber = track
            )
        }
        return null
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
        val cleanMime = if (mimeType.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
        payloadOut.write(cleanMime.toByteArray(StandardCharsets.ISO_8859_1))
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
     * Uses RandomAccessFile seeking to ensure 100% byte-exact streaming.
     */
    fun exportMp3(sourceFile: File, newMetadata: Mp3Metadata, outputStream: OutputStream) {
        val offsets = findAudioStreamOffsets(sourceFile)

        // 1. Write the new ID3v2.3 tag
        val id3v2Bytes = buildId3v23Tag(newMetadata)
        outputStream.write(id3v2Bytes)

        // 2. Stream untouched raw audio stream using RandomAccessFile seeking
        RandomAccessFile(sourceFile, "r").use { raf ->
            raf.seek(offsets.audioStartOffset)
            var bytesRemaining = offsets.audioEndOffset - offsets.audioStartOffset
            val buffer = ByteArray(65536)
            while (bytesRemaining > 0) {
                val toRead = minOf(bytesRemaining, buffer.size.toLong()).toInt()
                val read = raf.read(buffer, 0, toRead)
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
