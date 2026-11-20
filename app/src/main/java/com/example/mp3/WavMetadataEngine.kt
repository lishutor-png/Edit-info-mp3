package com.example.mp3

import android.content.Context
import android.media.MediaMetadataRetriever
import com.mpatric.mp3agic.ID3v2TagFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Native RIFF / WAVE (PCM) metadata & cover art engine.
 *
 * Responsibilities:
 * 1. Parses and preserves 'fmt ', 'data', and technical chunks ('fact', 'bext', etc.) bit-for-bit.
 * 2. Never re-encodes PCM audio; preserves exact sample rate, bit depth, channels, and duration.
 * 3. Embeds standard RIFF 'LIST'/'INFO' chunk (for FFmpeg/Windows/MediaExtractor) AND
 *    standard RIFF 'id3 ' chunk containing ID3v2.3 + APIC cover art (for AIMP, TagLib, etc.).
 * 4. Strictly maintains 2-byte RIFF word alignment padding and accurate master RIFF chunk size.
 */
object WavMetadataEngine {

    private data class RiffChunk(
        val id: String,
        val payloadOffset: Long,
        val payloadSize: Long,
        val listType: String? = null
    )

    /**
     * Extract metadata, cover art, and exact PCM duration from a WAV file.
     */
    fun extractMetadata(context: Context?, file: File): Mp3Metadata {
        var title = ""
        var artist = ""
        var album = ""
        var genre = ""
        var year = ""
        var trackNumber = ""
        var durationMs = 0L
        var coverBytes: ByteArray? = null
        var coverMime = "image/jpeg"

        var byteRate = 0L
        var dataSize = 0L

        try {
            RandomAccessFile(file, "r").use { raf ->
                val riffOffset = findRiffWaveOffset(raf, file.length())
                if (riffOffset >= 0) {
                    val chunks = scanChunks(raf, riffOffset, file.length())

                    // 1. Read 'fmt ' & 'data' for exact PCM duration
                    val fmtChunk = chunks.find { it.id == "fmt " }
                    if (fmtChunk != null && fmtChunk.payloadSize >= 16) {
                        val fmtBytes = ByteArray(16)
                        raf.seek(fmtChunk.payloadOffset)
                        raf.readFully(fmtBytes)
                        byteRate = readUInt32LE(fmtBytes, 8)
                    }
                    val dataChunk = chunks.find { it.id == "data" }
                    if (dataChunk != null) {
                        dataSize = dataChunk.payloadSize
                    }
                    if (byteRate > 0L && dataSize > 0L) {
                        durationMs = (dataSize * 1000L) / byteRate
                    }

                    // 2. Read 'id3 ' / 'ID3 ' chunk (contains ID3v2 tags + APIC cover art)
                    val id3Chunk = chunks.find { it.id.equals("id3 ", ignoreCase = true) }
                    if (id3Chunk != null && id3Chunk.payloadSize in 10L..25_000_000L) {
                        val id3Bytes = ByteArray(id3Chunk.payloadSize.toInt())
                        raf.seek(id3Chunk.payloadOffset)
                        raf.readFully(id3Bytes)
                        try {
                            val id3v2 = ID3v2TagFactory.createTag(id3Bytes)
                            if (id3v2 != null) {
                                title = id3v2.title ?: ""
                                artist = id3v2.artist ?: ""
                                album = id3v2.album ?: ""
                                year = id3v2.year ?: ""
                                trackNumber = id3v2.track ?: ""
                                genre = id3v2.genreDescription ?: ""
                                coverBytes = id3v2.albumImage
                                coverMime = id3v2.albumImageMimeType ?: "image/jpeg"
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 3. Fallback to 'LIST'/'INFO' chunk for missing text tags
                    val infoChunk = chunks.find { it.id == "LIST" && it.listType == "INFO" }
                    if (infoChunk != null && infoChunk.payloadSize in 4L..5_000_000L) {
                        val listBytes = ByteArray(infoChunk.payloadSize.toInt())
                        raf.seek(infoChunk.payloadOffset)
                        raf.readFully(listBytes)
                        val infoMap = parseInfoSubChunks(listBytes)
                        if (title.isBlank()) title = infoMap["INAM"] ?: ""
                        if (artist.isBlank()) artist = infoMap["IART"] ?: ""
                        if (album.isBlank()) album = infoMap["IPRD"] ?: ""
                        if (year.isBlank()) year = infoMap["ICRD"] ?: ""
                        if (genre.isBlank()) genre = infoMap["IGNR"] ?: ""
                        if (trackNumber.isBlank()) trackNumber = infoMap["ITRK"] ?: infoMap["TRCK"] ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Fallback to Android MediaMetadataRetriever when Context is available
        if (context != null) {
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
                if (durationMs <= 0L) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                        durationMs = it
                    }
                }
                if (coverBytes == null) {
                    coverBytes = retriever.embeddedPicture
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Exception) {}
            }
        }

        if (title.isBlank()) {
            title = file.nameWithoutExtension.ifBlank { "Audio" }
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
            coverBitmap = bitmap,
            audioFormat = AudioFormat.WAV
        )
    }

    /**
     * Export WAV with updated RIFF 'LIST'/'INFO' metadata and 'id3 ' (ID3v2.3 + APIC cover art) chunk.
     * Preserves 'fmt ', 'data', and all non-metadata chunks byte-for-byte without any re-encoding.
     */
    fun exportWav(sourceFile: File, newMetadata: Mp3Metadata, outputStream: OutputStream) {
        val fileLength = sourceFile.length()
        RandomAccessFile(sourceFile, "r").use { raf ->
            val riffOffset = findRiffWaveOffset(raf, fileLength)
            if (riffOffset < 0) {
                throw IllegalArgumentException("Invalid WAV file: missing RIFF/WAVE header")
            }

            val allChunks = scanChunks(raf, riffOffset, fileLength)
            if (allChunks.none { it.id == "fmt " } || allChunks.none { it.id == "data" }) {
                throw IllegalArgumentException("Invalid WAV file: missing 'fmt ' or 'data' chunk")
            }

            // Keep all audio and non-metadata chunks untouched; strip old INFO and id3 chunks
            val keptChunks = allChunks.filterNot { chunk ->
                chunk.id.equals("id3 ", ignoreCase = true) ||
                    (chunk.id == "LIST" && chunk.listType == "INFO")
            }

            // Build new RIFF LIST/INFO chunk and ID3v2.3 'id3 ' chunk
            val listInfoChunkBytes = buildListInfoChunk(newMetadata)
            val id3ChunkBytes = buildId3Chunk(newMetadata)

            var riffPayloadSize = 4L // "WAVE"
            for (chunk in keptChunks) {
                val alignedSize = chunk.payloadSize + (chunk.payloadSize and 1L)
                riffPayloadSize += 8L + alignedSize
            }
            riffPayloadSize += listInfoChunkBytes.size.toLong()
            riffPayloadSize += id3ChunkBytes.size.toLong()

            if (riffPayloadSize > 0xFFFFFFFFL) {
                throw IllegalStateException("WAV output exceeds 4 GB RIFF limit")
            }

            // 1. Write RIFF header
            val riffHeader = ByteArray(12)
            riffHeader[0] = 'R'.code.toByte()
            riffHeader[1] = 'I'.code.toByte()
            riffHeader[2] = 'F'.code.toByte()
            riffHeader[3] = 'F'.code.toByte()
            writeUInt32LE(riffHeader, 4, riffPayloadSize)
            riffHeader[8] = 'W'.code.toByte()
            riffHeader[9] = 'A'.code.toByte()
            riffHeader[10] = 'V'.code.toByte()
            riffHeader[11] = 'E'.code.toByte()
            outputStream.write(riffHeader)

            // 2. Stream kept chunks (fmt, fact, data, etc.) untouched
            val buffer = ByteArray(65536)
            val chunkHeader = ByteArray(8)
            for (chunk in keptChunks) {
                val idBytes = chunk.id.toByteArray(StandardCharsets.US_ASCII)
                System.arraycopy(idBytes, 0, chunkHeader, 0, 4)
                writeUInt32LE(chunkHeader, 4, chunk.payloadSize)
                outputStream.write(chunkHeader)

                raf.seek(chunk.payloadOffset)
                var remaining = chunk.payloadSize
                while (remaining > 0L) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    outputStream.write(buffer, 0, read)
                    remaining -= read
                }

                // RIFF word alignment pad byte for odd-sized payloads
                if ((chunk.payloadSize and 1L) != 0L) {
                    outputStream.write(0)
                }
            }

            // 3. Write updated RIFF LIST/INFO chunk and 'id3 ' chunk
            if (listInfoChunkBytes.isNotEmpty()) {
                outputStream.write(listInfoChunkBytes)
            }
            outputStream.write(id3ChunkBytes)
            outputStream.flush()
        }
    }

    private fun findRiffWaveOffset(raf: RandomAccessFile, fileLength: Long): Long {
        if (fileLength < 12L) return -1L
        val header = ByteArray(12)
        raf.seek(0)
        raf.readFully(header)

        if (isRiffWave(header)) {
            return 0L
        }

        // Handle rare case where an ID3v2 tag was mistakenly prepended before RIFF
        if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
            val flags = header[5].toInt() and 0xFF
            val s0 = header[6].toInt() and 0x7F
            val s1 = header[7].toInt() and 0x7F
            val s2 = header[8].toInt() and 0x7F
            val s3 = header[9].toInt() and 0x7F
            val payloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
            var id3Size = 10L + payloadSize
            if ((flags and 0x10) != 0) id3Size += 10L

            if (id3Size <= fileLength - 12L) {
                raf.seek(id3Size)
                raf.readFully(header)
                if (isRiffWave(header)) {
                    return id3Size
                }
            }
        }

        // Scan first 4096 bytes as fallback
        val scanLen = minOf(fileLength, 4096L).toInt()
        val scanBuf = ByteArray(scanLen)
        raf.seek(0)
        val read = raf.read(scanBuf)
        for (i in 0..(read - 12)) {
            if (scanBuf[i] == 'R'.code.toByte() &&
                scanBuf[i + 1] == 'I'.code.toByte() &&
                scanBuf[i + 2] == 'F'.code.toByte() &&
                scanBuf[i + 3] == 'F'.code.toByte() &&
                scanBuf[i + 8] == 'W'.code.toByte() &&
                scanBuf[i + 9] == 'A'.code.toByte() &&
                scanBuf[i + 10] == 'V'.code.toByte() &&
                scanBuf[i + 11] == 'E'.code.toByte()
            ) {
                return i.toLong()
            }
        }

        return -1L
    }

    private fun isRiffWave(buf: ByteArray): Boolean {
        return buf.size >= 12 &&
            buf[0] == 'R'.code.toByte() &&
            buf[1] == 'I'.code.toByte() &&
            buf[2] == 'F'.code.toByte() &&
            buf[3] == 'F'.code.toByte() &&
            buf[8] == 'W'.code.toByte() &&
            buf[9] == 'A'.code.toByte() &&
            buf[10] == 'V'.code.toByte() &&
            buf[11] == 'E'.code.toByte()
    }

    private fun scanChunks(raf: RandomAccessFile, riffOffset: Long, fileLength: Long): List<RiffChunk> {
        val chunks = mutableListOf<RiffChunk>()
        var offset = riffOffset + 12L
        val chunkHeader = ByteArray(8)

        while (offset <= fileLength - 8L) {
            raf.seek(offset)
            if (raf.read(chunkHeader) < 8) break

            // Validate printable ASCII FourCC
            val validId = (0..3).all { idx ->
                val c = chunkHeader[idx].toInt() and 0xFF
                c in 0x20..0x7E
            }
            if (!validId) break

            val id = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
            val declaredSize = readUInt32LE(chunkHeader, 4)
            val payloadOffset = offset + 8L

            if (payloadOffset > fileLength) break

            val actualSize = minOf(declaredSize, fileLength - payloadOffset)
            var listType: String? = null
            if (id == "LIST" && actualSize >= 4L) {
                val typeBuf = ByteArray(4)
                raf.seek(payloadOffset)
                if (raf.read(typeBuf) == 4) {
                    listType = String(typeBuf, StandardCharsets.US_ASCII)
                }
            }

            chunks.add(RiffChunk(id, payloadOffset, actualSize, listType))

            var nextOffset = payloadOffset + actualSize
            if ((actualSize and 1L) != 0L && nextOffset < fileLength) {
                nextOffset += 1L
            }
            if (nextOffset <= offset) break
            offset = nextOffset
        }

        return chunks
    }

    private fun parseInfoSubChunks(listPayload: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 4 // skip "INFO"
        while (offset <= listPayload.size - 8) {
            val subId = String(listPayload, offset, 4, StandardCharsets.US_ASCII)
            val subSize = readUInt32LE(listPayload, offset + 4).toInt()
            val payloadStart = offset + 8
            if (subSize < 0 || payloadStart + subSize > listPayload.size) break

            val value = String(listPayload, payloadStart, subSize, StandardCharsets.UTF_8)
                .trimEnd('\u0000', ' ')
                .trim()
            if (value.isNotEmpty()) {
                result[subId] = value
            }

            offset = payloadStart + subSize + (subSize and 1)
        }
        return result
    }

    private fun buildListInfoChunk(metadata: Mp3Metadata): ByteArray {
        val subChunksOut = ByteArrayOutputStream()
        val title = metadata.title.ifBlank { "Audio" }.trim()
        writeInfoSubChunk(subChunksOut, "INAM", title)

        if (metadata.artist.isNotBlank()) {
            writeInfoSubChunk(subChunksOut, "IART", metadata.artist.trim())
        }
        if (metadata.album.isNotBlank()) {
            writeInfoSubChunk(subChunksOut, "IPRD", metadata.album.trim())
        }
        if (metadata.year.isNotBlank()) {
            writeInfoSubChunk(subChunksOut, "ICRD", metadata.year.trim())
        }
        if (metadata.genre.isNotBlank()) {
            writeInfoSubChunk(subChunksOut, "IGNR", metadata.genre.trim())
        }
        if (metadata.trackNumber.isNotBlank()) {
            writeInfoSubChunk(subChunksOut, "ITRK", metadata.trackNumber.trim())
        }
        writeInfoSubChunk(subChunksOut, "ICMT", "LishEdInfo")

        val subBytes = subChunksOut.toByteArray()
        val listPayloadSize = 4 + subBytes.size
        val totalSize = 8 + listPayloadSize + (listPayloadSize and 1)
        val chunk = ByteArray(totalSize)

        chunk[0] = 'L'.code.toByte()
        chunk[1] = 'I'.code.toByte()
        chunk[2] = 'S'.code.toByte()
        chunk[3] = 'T'.code.toByte()
        writeUInt32LE(chunk, 4, listPayloadSize.toLong())
        chunk[8] = 'I'.code.toByte()
        chunk[9] = 'N'.code.toByte()
        chunk[10] = 'F'.code.toByte()
        chunk[11] = 'O'.code.toByte()
        System.arraycopy(subBytes, 0, chunk, 12, subBytes.size)
        return chunk
    }

    private fun writeInfoSubChunk(out: ByteArrayOutputStream, fourCc: String, value: String) {
        val strBytes = value.toByteArray(StandardCharsets.UTF_8)
        val payloadSize = strBytes.size + 1 // null-terminated
        val header = ByteArray(8)
        System.arraycopy(fourCc.toByteArray(StandardCharsets.US_ASCII), 0, header, 0, 4)
        writeUInt32LE(header, 4, payloadSize.toLong())
        out.write(header)
        out.write(strBytes)
        out.write(0) // null terminator
        if ((payloadSize and 1) != 0) {
            out.write(0) // RIFF word-alignment pad
        }
    }

    private fun buildId3Chunk(metadata: Mp3Metadata): ByteArray {
        val id3Bytes = Mp3Id3Engine.buildId3v23Tag(metadata)
        val pad = id3Bytes.size and 1
        val chunk = ByteArray(8 + id3Bytes.size + pad)
        chunk[0] = 'i'.code.toByte()
        chunk[1] = 'd'.code.toByte()
        chunk[2] = '3'.code.toByte()
        chunk[3] = ' '.code.toByte()
        writeUInt32LE(chunk, 4, id3Bytes.size.toLong())
        System.arraycopy(id3Bytes, 0, chunk, 8, id3Bytes.size)
        return chunk
    }

    private fun readUInt32LE(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFF) or
            ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or
            ((b[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun writeUInt32LE(b: ByteArray, offset: Int, value: Long) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        b[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        b[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
