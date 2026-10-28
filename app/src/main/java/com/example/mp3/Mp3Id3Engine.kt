package com.example.mp3

import android.content.Context
import android.media.MediaMetadataRetriever
import com.mpatric.mp3agic.ID3v1Tag
import com.mpatric.mp3agic.ID3v23Tag
import com.mpatric.mp3agic.Mp3File
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Robust, universal MP3 ID3 engine.
 * 
 * Powered by standard mp3agic parser + custom Stagefright-compliant fallback:
 * 1. Writes standard ID3v2.3 tag with ISO-8859-1 / UTF-16 BOM and APIC front cover.
 * 2. Copies raw MPEG audio bitstream frame-for-frame preserving Xing/VBR/Info headers.
 * 3. Never truncates trailing MPEG frame bytes, eliminating ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
 * 4. Ensures 100% compatibility across both third-party players (AIMP) and stock OEM Android players.
 */
object Mp3Id3Engine {

    /**
     * Extract existing metadata by combining mp3agic and MediaMetadataRetriever.
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
        var coverMime = "image/jpeg"

        // 1. Try mp3agic parser
        try {
            val mp3file = Mp3File(file.absolutePath, false)
            if (mp3file.hasId3v2Tag()) {
                val tag = mp3file.id3v2Tag
                title = tag.title ?: ""
                artist = tag.artist ?: ""
                album = tag.album ?: ""
                year = tag.year ?: ""
                trackNumber = tag.track ?: ""
                genre = tag.genreDescription ?: ""
                coverBytes = tag.albumImage
                coverMime = tag.albumImageMimeType ?: "image/jpeg"
            } else if (mp3file.hasId3v1Tag()) {
                val tag = mp3file.id3v1Tag
                title = tag.title ?: ""
                artist = tag.artist ?: ""
                album = tag.album ?: ""
                year = tag.year ?: ""
                trackNumber = tag.track ?: ""
            }
            durationMs = mp3file.lengthInMilliseconds
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to MediaMetadataRetriever for duration and missing fields
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            if (title.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let {
                    if (it.isNotBlank()) title = it
                }
            }
            if (artist.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let {
                    if (it.isNotBlank()) artist = it
                }
            }
            if (album.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let {
                    if (it.isNotBlank()) album = it
                }
            }
            if (genre.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let {
                    if (it.isNotBlank()) genre = it
                }
            }
            if (year.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let {
                    if (it.isNotBlank()) year = it
                }
            }
            if (trackNumber.isBlank()) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let {
                    if (it.isNotBlank()) trackNumber = it
                }
            }
            if (durationMs <= 0) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    durationMs = it
                }
            }
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
            coverMimeType = coverMime,
            durationMs = durationMs,
            fileSize = file.length(),
            fileName = file.name,
            coverBitmap = bitmap
        )
    }

    /**
     * Export MP3 with updated metadata and album cover art.
     * Uses 100% byte-exact streaming multiplexer:
     * 1. Writes standard ID3v2.3 tag with ISO-8859-1 / UTF-16 BOM and APIC front cover (Picture Type 3).
     * 2. Copies raw MPEG audio bitstream frame-for-frame preserving Xing/VBR/Info headers.
     * 3. Never truncates trailing MPEG frame bytes, eliminating ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
     * 4. Appends standard 128-byte ID3v1 trailer for universal legacy compatibility.
     */
    fun exportMp3(sourceFile: File, newMetadata: Mp3Metadata, outputStream: OutputStream) {
        val offsets = findAudioStreamOffsets(sourceFile)

        // 1. Write clean ID3v2.3 tag
        val id3v2Bytes = buildId3v23Tag(newMetadata)
        outputStream.write(id3v2Bytes)

        // 2. Stream untouched raw audio bitstream
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

        // 3. Write ID3v1 trailer
        val id3v1Bytes = buildId3v1Tag(newMetadata)
        outputStream.write(id3v1Bytes)
        outputStream.flush()
    }

    data class AudioOffsets(
        val audioStartOffset: Long,
        val audioEndOffset: Long,
        val existingId3v2Size: Long,
        val hasId3v1: Boolean
    )

    fun findAudioStreamOffsets(file: File): AudioOffsets {
        val fileLength = file.length()
        if (fileLength < 10) {
            return AudioOffsets(0L, fileLength, 0L, false)
        }

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
                    id3v2Size += 10L
                }
            }

            if (id3v2Size > fileLength) {
                id3v2Size = 0L
            }

            val audioStart = findFirstMpegSyncOffset(raf, id3v2Size, fileLength)

            var audioEnd = fileLength
            var hasId3v1 = false

            if (fileLength >= 128) {
                raf.seek(fileLength - 128)
                val tag = ByteArray(3)
                raf.readFully(tag)
                if (tag[0] == 'T'.code.toByte() && tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()) {
                    hasId3v1 = true
                    audioEnd = fileLength - 128
                }
            }

            // Detect and strip APEv2 tag if present (either before ID3v1 or at EOF)
            if (audioEnd >= 32) {
                raf.seek(audioEnd - 32)
                val apeHeader = ByteArray(8)
                if (raf.read(apeHeader, 0, 8) == 8) {
                    val str = String(apeHeader, StandardCharsets.US_ASCII)
                    if (str == "APETAGEX") {
                        val sizeBytes = ByteArray(4)
                        raf.seek(audioEnd - 32 + 12)
                        if (raf.read(sizeBytes, 0, 4) == 4) {
                            val b0 = sizeBytes[0].toLong() and 0xFF
                            val b1 = sizeBytes[1].toLong() and 0xFF
                            val b2 = sizeBytes[2].toLong() and 0xFF
                            val b3 = sizeBytes[3].toLong() and 0xFF
                            val apeSize = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                            if (apeSize in 32..500000 && audioEnd >= apeSize) {
                                audioEnd -= apeSize
                            }
                        }
                    }
                }
            }

            if (audioEnd <= audioStart) {
                audioEnd = fileLength
            }

            return AudioOffsets(audioStart, audioEnd, id3v2Size, hasId3v1)
        }
    }

    private fun findFirstMpegSyncOffset(raf: RandomAccessFile, startSearch: Long, fileLength: Long): Long {
        val safeStart = startSearch.coerceIn(0L, (fileLength - 4).coerceAtLeast(0L))
        if (fileLength < 4) return safeStart

        // 1. Direct check: Is safeStart ALREADY the beginning of consecutive valid MPEG frames?
        if (verifyConsecutiveFrames(raf, safeStart, fileLength)) {
            return safeStart
        }

        // 2. Skip leading 0x00 or 0xFF padding right after ID3 tag or at start of file
        var current = safeStart
        val leadBuffer = ByteArray(minOf(4096, (fileLength - current).toInt()))
        raf.seek(current)
        val leadRead = raf.read(leadBuffer)
        if (leadRead > 0) {
            var skipCount = 0
            while (skipCount < leadRead && (leadBuffer[skipCount] == 0x00.toByte())) {
                skipCount++
            }
            if (skipCount > 0 && skipCount < leadRead) {
                val candidateAfterZeros = current + skipCount
                if (verifyConsecutiveFrames(raf, candidateAfterZeros, fileLength)) {
                    return candidateAfterZeros
                }
            }
        }

        // 3. Scan forward up to 64KB, requiring MULTI-FRAME confirmation (prevents false sync inside audio payload)
        val maxSearch = minOf(safeStart + 65536L, (fileLength - 4).coerceAtLeast(0L))
        var searchOffset = safeStart
        val bufferSize = 8192
        val buffer = ByteArray(bufferSize + 4)

        while (searchOffset < maxSearch) {
            raf.seek(searchOffset)
            val bytesToRead = minOf(bufferSize.toLong(), fileLength - searchOffset).toInt()
            if (bytesToRead < 4) break
            val read = raf.read(buffer, 0, bytesToRead)
            if (read < 4) break

            for (i in 0 until (read - 3)) {
                val b0 = buffer[i].toInt() and 0xFF
                val b1 = buffer[i + 1].toInt() and 0xFF

                if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                    val candidatePos = searchOffset + i
                    if (verifyConsecutiveFrames(raf, candidatePos, fileLength)) {
                        return candidatePos
                    }
                }
            }
            searchOffset += (read - 3)
        }

        // Fallback: If no multi-frame sequence confirmed, trust safeStart
        return safeStart
    }

    /**
     * Verifies that candidateOffset is the start of a real MPEG stream by verifying that
     * candidate frame and the consecutive following frame have matching MPEG version and sample rate.
     */
    private fun verifyConsecutiveFrames(raf: RandomAccessFile, candidateOffset: Long, fileLength: Long): Boolean {
        if (candidateOffset + 4 > fileLength) return false

        val header1 = ByteArray(4)
        raf.seek(candidateOffset)
        if (raf.read(header1, 0, 4) < 4) return false

        val size1 = getMpegFrameSize(header1, 0, 4)
        if (size1 <= 0) return false

        // Check if this frame has a Xing / Info header (VBR container header)
        if (hasXingHeader(raf, candidateOffset, fileLength)) {
            return true
        }

        val nextOffset = candidateOffset + size1
        if (nextOffset >= fileLength) {
            // Reached exact end of file on a valid frame
            return true
        }

        if (nextOffset + 4 > fileLength) {
            return false
        }

        val header2 = ByteArray(4)
        raf.seek(nextOffset)
        if (raf.read(header2, 0, 4) < 4) return false

        val size2 = getMpegFrameSize(header2, 0, 4)
        if (size2 <= 0) return false

        // Verify version and sample rate match between consecutive frames
        val v1 = (header1[1].toInt() shr 3) and 0x03
        val v2 = (header2[1].toInt() shr 3) and 0x03
        val l1 = (header1[1].toInt() shr 1) and 0x03
        val l2 = (header2[1].toInt() shr 1) and 0x03
        val sr1 = (header1[2].toInt() shr 2) and 0x03
        val sr2 = (header2[2].toInt() shr 2) and 0x03

        return (v1 == v2 && l1 == l2 && sr1 == sr2)
    }

    private fun hasXingHeader(raf: RandomAccessFile, frameOffset: Long, fileLength: Long): Boolean {
        // Xing / Info signature is located at frameOffset + 36 (for MPEG1 stereo) or + 21 (MPEG1 mono / MPEG2 stereo)
        for (offset in intArrayOf(36, 21, 13)) {
            val target = frameOffset + offset
            if (target + 4 <= fileLength) {
                raf.seek(target)
                val sig = ByteArray(4)
                if (raf.read(sig, 0, 4) == 4) {
                    val str = String(sig, StandardCharsets.US_ASCII)
                    if (str == "Xing" || str == "Info" || str == "VBRI") {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun getMpegFrameSize(buffer: ByteArray, offset: Int, bufferLen: Int): Int {
        if (offset + 4 > bufferLen) return -1
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF

        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return -1
        val versionId = (b1 shr 3) and 0x03
        if (versionId == 1) return -1 // Reserved version
        val layerId = (b1 shr 1) and 0x03
        if (layerId == 0) return -1 // Reserved layer

        val bitrateIdx = (b2 shr 4) and 0x0F
        if (bitrateIdx == 15) return -1 // Bad bitrate
        val sampleRateIdx = (b2 shr 2) and 0x03
        if (sampleRateIdx == 3) return -1 // Reserved sample rate
        val padding = (b2 shr 1) and 0x01

        val sampleRate = when (versionId) {
            3 -> when (sampleRateIdx) {
                0 -> 44100
                1 -> 48000
                2 -> 32000
                else -> return -1
            }
            2 -> when (sampleRateIdx) {
                0 -> 22050
                1 -> 24000
                2 -> 16000
                else -> return -1
            }
            0 -> when (sampleRateIdx) {
                0 -> 11025
                1 -> 12000
                2 -> 8000
                else -> return -1
            }
            else -> return -1
        }

        // If bitrateIdx is 0 (Free format / Xing header), use nominal 128 kbps (or 64 kbps for MPEG-2)
        val nominalBitrate = if (versionId == 3) 128 else 64
        val bitrate = if (bitrateIdx == 0) {
            nominalBitrate
        } else if (versionId == 3) {
            when (layerId) {
                1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIdx]
                2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)[bitrateIdx]
                3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)[bitrateIdx]
                else -> return -1
            }
        } else {
            when (layerId) {
                1, 2, 3 -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIdx]
                else -> return -1
            }
        }

        return when (layerId) {
            1 -> { // Layer III (MP3)
                val coefficient = if (versionId == 3) 144 else 72
                (coefficient * bitrate * 1000) / sampleRate + padding
            }
            2 -> (144 * bitrate * 1000) / sampleRate + padding
            3 -> ((12 * bitrate * 1000) / sampleRate + padding) * 4
            else -> -1
        }
    }

    fun buildId3v23Tag(metadata: Mp3Metadata): ByteArray {
        val framesOut = ByteArrayOutputStream()

        val title = metadata.title.ifBlank { "Lagu" }.trim()
        val artist = metadata.artist.ifBlank { "Artis" }.trim()
        val album = metadata.album.ifBlank { title }.trim()

        writeTextFrame(framesOut, "TIT2", title)
        writeTextFrame(framesOut, "TPE1", artist)
        writeTextFrame(framesOut, "TALB", album)
        if (metadata.year.isNotBlank()) writeTextFrame(framesOut, "TYER", metadata.year.trim())
        if (metadata.genre.isNotBlank()) writeTextFrame(framesOut, "TCON", metadata.genre.trim())
        if (metadata.trackNumber.isNotBlank()) writeTextFrame(framesOut, "TRCK", metadata.trackNumber.trim())

        val cover = metadata.coverImageBytes
        if (cover != null && cover.isNotEmpty()) {
            writeApicFrame(framesOut, cover, metadata.coverMimeType)
        }

        val framesBytes = framesOut.toByteArray()
        val framesSize = framesBytes.size

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 0x03 // ID3v2.3
        header[4] = 0x00
        header[5] = 0x00

        header[6] = ((framesSize shr 21) and 0x7F).toByte()
        header[7] = ((framesSize shr 14) and 0x7F).toByte()
        header[8] = ((framesSize shr 7) and 0x7F).toByte()
        header[9] = (framesSize and 0x7F).toByte()

        val fullTag = ByteArray(10 + framesSize)
        System.arraycopy(header, 0, fullTag, 0, 10)
        System.arraycopy(framesBytes, 0, fullTag, 10, framesSize)
        return fullTag
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val payloadOut = ByteArrayOutputStream()
        val isAscii = text.all { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }

        if (isAscii) {
            payloadOut.write(0x00)
            payloadOut.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        } else {
            payloadOut.write(0x01)
            payloadOut.write(0xFF)
            payloadOut.write(0xFE)
            payloadOut.write(text.toByteArray(StandardCharsets.UTF_16LE))
        }

        val payload = payloadOut.toByteArray()
        out.write(frameId.toByteArray(StandardCharsets.US_ASCII))

        val size = payload.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)

        out.write(0x00)
        out.write(0x00)
        out.write(payload)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        val payloadOut = ByteArrayOutputStream()
        payloadOut.write(0x00)

        val cleanMime = if (mimeType.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
        payloadOut.write(cleanMime.toByteArray(StandardCharsets.ISO_8859_1))
        payloadOut.write(0x00)

        payloadOut.write(0x03)
        payloadOut.write(0x00)

        payloadOut.write(imageBytes)

        val payload = payloadOut.toByteArray()
        out.write("APIC".toByteArray(StandardCharsets.US_ASCII))

        val size = payload.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)

        out.write(0x00)
        out.write(0x00)
        out.write(payload)
    }

    fun buildId3v1Tag(metadata: Mp3Metadata): ByteArray {
        val tag = ByteArray(128)
        tag[0] = 'T'.code.toByte()
        tag[1] = 'A'.code.toByte()
        tag[2] = 'G'.code.toByte()

        writeIsoString(tag, 3, 30, metadata.title)
        writeIsoString(tag, 33, 30, metadata.artist)
        writeIsoString(tag, 63, 30, metadata.album)
        writeIsoString(tag, 93, 4, metadata.year)
        writeIsoString(tag, 97, 28, "LishEdInfo")
        tag[125] = 0x00
        tag[126] = (metadata.trackNumber.toIntOrNull() ?: 0).coerceIn(0, 255).toByte()
        tag[127] = 255.toByte()

        return tag
    }

    private fun writeIsoString(dest: ByteArray, offset: Int, maxLen: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
        val copyLen = bytes.size.coerceAtMost(maxLen)
        System.arraycopy(bytes, 0, dest, offset, copyLen)
    }
}
