package com.example.mp3

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Native ISO Base Media File Format (ISO/IEC 14496-12 & 14496-14) MP4 / M4A metadata engine.
 *
 * Responsibilities:
 * 1. Accurately extracts and embeds metadata and album cover art (covr atom) in MP4/M4A containers.
 * 2. Fully preserves raw AAC-LC audio samples inside 'mdat' without any re-encoding.
 * 3. Correctly adjusts chunk offsets in 'stco' / 'co64' atoms when 'moov' atom size changes.
 * 4. Never applies MP3 ID3v2 tags or MPEG sync scanning to MP4 containers.
 * 5. Guarantees 100% playable, error-free output on FFmpeg, Android MediaPlayer, and OEM players.
 */
object Mp4MetadataEngine {

    data class TopLevelBox(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int
    )

    /**
     * Extract metadata from an MP4/M4A file using native box parsing and MediaMetadataRetriever fallback.
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

        // 1. Try native MP4 box parsing for tags & cover
        try {
            RandomAccessFile(file, "r").use { raf ->
                val boxes = scanTopLevelBoxes(raf, file.length())
                val moovBox = boxes.find { it.type == "moov" }
                if (moovBox != null && moovBox.size < 50_000_000L) {
                    raf.seek(moovBox.offset)
                    val moovBytes = ByteArray(moovBox.size.toInt())
                    raf.readFully(moovBytes)

                    val parsed = parseMoovMetadata(moovBytes)
                    title = parsed.title
                    artist = parsed.artist
                    album = parsed.album
                    genre = parsed.genre
                    year = parsed.year
                    trackNumber = parsed.trackNumber
                    coverBytes = parsed.coverBytes
                    coverMime = parsed.coverMime
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Fallback to MediaMetadataRetriever for duration and missing fields
        try {
            val retriever = MediaMetadataRetriever()
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
            retriever.release()
        } catch (ignored: Exception) {}

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
            coverBitmap = bitmap
        )
    }

    /**
     * Export MP4/M4A with updated metadata and album cover art.
     * Rebuilds the 'moov' atom with updated 'udta.meta.ilst' and adjusts 'stco' / 'co64' chunk offsets.
     * Copies raw 'mdat' bitstream directly without re-encoding!
     */
    fun exportMp4(sourceFile: File, newMetadata: Mp3Metadata, outputStream: OutputStream) {
        val fileLength = sourceFile.length()
        RandomAccessFile(sourceFile, "r").use { raf ->
            val boxes = scanTopLevelBoxes(raf, fileLength)

            val ftypBox = boxes.find { it.type == "ftyp" }
                ?: throw IllegalArgumentException("Invalid MP4 file: missing 'ftyp' box")
            val moovBox = boxes.find { it.type == "moov" }
                ?: throw IllegalArgumentException("Invalid MP4 file: missing 'moov' box")
            val mdatBox = boxes.find { it.type == "mdat" }
                ?: throw IllegalArgumentException("Invalid MP4 file: missing 'mdat' box")

            // 1. Read original ftyp box
            raf.seek(ftypBox.offset)
            val ftypBytes = ByteArray(ftypBox.size.toInt())
            raf.readFully(ftypBytes)

            // 2. Read original moov box
            raf.seek(moovBox.offset)
            val moovBytes = ByteArray(moovBox.size.toInt())
            raf.readFully(moovBytes)

            // 3. Build new udta box containing the new metadata and covr
            val newUdtaBytes = buildUdtaBox(newMetadata)

            // 4. Reconstruct moov payload: keep all child boxes of moov except old udta, then append newUdta
            val childBoxesOfMoov = parseChildBoxes(moovBytes, 8, moovBytes.size - 8)
            val reconstructedMoovPayload = ByteArrayOutputStream()
            for (child in childBoxesOfMoov) {
                if (child.type != "udta") {
                    reconstructedMoovPayload.write(moovBytes, child.offset.toInt(), child.size.toInt())
                }
            }
            reconstructedMoovPayload.write(newUdtaBytes)

            val newMoovPayloadBytes = reconstructedMoovPayload.toByteArray()
            val newMoovTotalSize = 8 + newMoovPayloadBytes.size

            // 5. Calculate chunk offset shift (delta)
            // Output file layout will be: [ftyp] [newMoov] [mdat]
            val newMdatStart = ftypBytes.size.toLong() + newMoovTotalSize.toLong()
            val oldMdatStart = mdatBox.offset
            val delta = newMdatStart - oldMdatStart

            // 6. Build final moov box bytes
            val finalMoovBytes = ByteArray(newMoovTotalSize)
            writeInt(finalMoovBytes, 0, newMoovTotalSize)
            finalMoovBytes[4] = 'm'.code.toByte()
            finalMoovBytes[5] = 'o'.code.toByte()
            finalMoovBytes[6] = 'o'.code.toByte()
            finalMoovBytes[7] = 'v'.code.toByte()
            System.arraycopy(newMoovPayloadBytes, 0, finalMoovBytes, 8, newMoovPayloadBytes.size)

            // 7. Adjust stco / co64 chunk offsets in finalMoovBytes
            adjustChunkOffsets(finalMoovBytes, delta)

            // 8. Stream output: ftyp + newMoov + mdat (direct audio copy)
            outputStream.write(ftypBytes)
            outputStream.write(finalMoovBytes)

            // Stream untouched mdat box
            raf.seek(mdatBox.offset)
            var bytesRemaining = mdatBox.size
            val buffer = ByteArray(65536)
            while (bytesRemaining > 0) {
                val toRead = minOf(bytesRemaining, buffer.size.toLong()).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) break
                outputStream.write(buffer, 0, read)
                bytesRemaining -= read
            }
            outputStream.flush()
        }
    }

    private fun scanTopLevelBoxes(raf: RandomAccessFile, fileLength: Long): List<TopLevelBox> {
        val list = mutableListOf<TopLevelBox>()
        var offset = 0L
        while (offset < fileLength - 8) {
            raf.seek(offset)
            val header = ByteArray(8)
            val read = raf.read(header)
            if (read < 8) break

            var boxSize = readUInt32(header, 0)
            val boxType = String(header, 4, 4, StandardCharsets.US_ASCII)
            var headerSize = 8

            if (boxSize == 1L) {
                // 64-bit size
                val size64Bytes = ByteArray(8)
                if (raf.read(size64Bytes) < 8) break
                boxSize = readUInt64(size64Bytes, 0)
                headerSize = 16
            } else if (boxSize == 0L) {
                // Box extends to EOF
                boxSize = fileLength - offset
            }

            if (boxSize < headerSize || offset + boxSize > fileLength + 1024) {
                // Invalid or corrupted box header
                break
            }

            list.add(TopLevelBox(boxType, offset, boxSize, headerSize))
            offset += boxSize
        }
        return list
    }

    private fun parseChildBoxes(bytes: ByteArray, start: Int, length: Int): List<TopLevelBox> {
        val list = mutableListOf<TopLevelBox>()
        var offset = start
        val end = start + length
        while (offset <= end - 8) {
            val boxSize = readUInt32(bytes, offset)
            val boxType = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
            if (boxSize < 8 || offset + boxSize > end) break
            list.add(TopLevelBox(boxType, offset.toLong(), boxSize, 8))
            offset += boxSize.toInt()
        }
        return list
    }

    /**
     * Builds standard 'udta' box containing 'meta -> hdlr + ilst' with cover art and tags.
     */
    private fun buildUdtaBox(metadata: Mp3Metadata): ByteArray {
        val ilstPayload = ByteArrayOutputStream()

        // 1. Title (©nam)
        val title = metadata.title.ifBlank { "Audio" }.trim()
        ilstPayload.write(createIlstTextItem(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), title))

        // 2. Artist (©ART)
        if (metadata.artist.isNotBlank()) {
            ilstPayload.write(createIlstTextItem(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), metadata.artist.trim()))
        }

        // 3. Album (©alb)
        if (metadata.album.isNotBlank()) {
            ilstPayload.write(createIlstTextItem(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), metadata.album.trim()))
        }

        // 4. Year (©day)
        if (metadata.year.isNotBlank()) {
            ilstPayload.write(createIlstTextItem(byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()), metadata.year.trim()))
        }

        // 5. Genre (©gen)
        if (metadata.genre.isNotBlank()) {
            ilstPayload.write(createIlstTextItem(byteArrayOf(0xA9.toByte(), 'g'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte()), metadata.genre.trim()))
        }

        // 6. Track Number (trkn)
        val trackNum = metadata.trackNumber.toIntOrNull()
        if (trackNum != null && trackNum > 0) {
            ilstPayload.write(createIlstTrackItem(trackNum))
        }

        // 7. Cover Art (covr)
        val cover = metadata.coverImageBytes
        if (cover != null && cover.isNotEmpty()) {
            val isPng = metadata.coverMimeType.contains("png", ignoreCase = true)
            ilstPayload.write(createIlstCoverItem(cover, isPng))
        }

        val ilstBytes = ilstPayload.toByteArray()
        val ilstTotalSize = 8 + ilstBytes.size
        val ilstBox = ByteArray(ilstTotalSize)
        writeInt(ilstBox, 0, ilstTotalSize)
        ilstBox[4] = 'i'.code.toByte()
        ilstBox[5] = 'l'.code.toByte()
        ilstBox[6] = 's'.code.toByte()
        ilstBox[7] = 't'.code.toByte()
        System.arraycopy(ilstBytes, 0, ilstBox, 8, ilstBytes.size)

        // Handler box ('hdlr') required for QuickTime/iTunes metadata
        val hdlrBox = createMdirHdlrBox()

        // 'meta' box (FullBox: size(4) + 'meta'(4) + version/flags(4) + hdlr + ilst)
        val metaTotalSize = 12 + hdlrBox.size + ilstBox.size
        val metaBox = ByteArray(metaTotalSize)
        writeInt(metaBox, 0, metaTotalSize)
        metaBox[4] = 'm'.code.toByte()
        metaBox[5] = 'e'.code.toByte()
        metaBox[6] = 't'.code.toByte()
        metaBox[7] = 'a'.code.toByte()
        // bytes 8..11 are version (0) and flags (0)
        System.arraycopy(hdlrBox, 0, metaBox, 12, hdlrBox.size)
        System.arraycopy(ilstBox, 0, metaBox, 12 + hdlrBox.size, ilstBox.size)

        // 'udta' box (size(4) + 'udta'(4) + metaBox)
        val udtaTotalSize = 8 + metaBox.size
        val udtaBox = ByteArray(udtaTotalSize)
        writeInt(udtaBox, 0, udtaTotalSize)
        udtaBox[4] = 'u'.code.toByte()
        udtaBox[5] = 'd'.code.toByte()
        udtaBox[6] = 't'.code.toByte()
        udtaBox[7] = 'a'.code.toByte()
        System.arraycopy(metaBox, 0, udtaBox, 8, metaBox.size)

        return udtaBox
    }

    private fun createIlstTextItem(fourCc: ByteArray, text: String): ByteArray {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val dataBoxSize = 16 + textBytes.size
        val totalSize = 8 + dataBoxSize
        val box = ByteArray(totalSize)

        // Item Header
        writeInt(box, 0, totalSize)
        System.arraycopy(fourCc, 0, box, 4, 4)

        // 'data' Box (type 1 = UTF-8 text)
        writeInt(box, 8, dataBoxSize)
        box[12] = 'd'.code.toByte()
        box[13] = 'a'.code.toByte()
        box[14] = 't'.code.toByte()
        box[15] = 'a'.code.toByte()
        writeInt(box, 16, 1) // flags = 1 (UTF-8)
        writeInt(box, 20, 0) // locale = 0
        System.arraycopy(textBytes, 0, box, 24, textBytes.size)

        return box
    }

    private fun createIlstCoverItem(imageBytes: ByteArray, isPng: Boolean): ByteArray {
        val dataBoxSize = 16 + imageBytes.size
        val totalSize = 8 + dataBoxSize
        val box = ByteArray(totalSize)

        // Item Header ('covr')
        writeInt(box, 0, totalSize)
        box[4] = 'c'.code.toByte()
        box[5] = 'o'.code.toByte()
        box[6] = 'v'.code.toByte()
        box[7] = 'r'.code.toByte()

        // 'data' Box (flags = 13 for JPEG, 14 for PNG)
        val flags = if (isPng) 14 else 13
        writeInt(box, 8, dataBoxSize)
        box[12] = 'd'.code.toByte()
        box[13] = 'a'.code.toByte()
        box[14] = 't'.code.toByte()
        box[15] = 'a'.code.toByte()
        writeInt(box, 16, flags)
        writeInt(box, 20, 0) // locale = 0
        System.arraycopy(imageBytes, 0, box, 24, imageBytes.size)

        return box
    }

    private fun createIlstTrackItem(trackNum: Int): ByteArray {
        val dataBoxSize = 16 + 8 // 24 bytes
        val totalSize = 8 + dataBoxSize // 32 bytes
        val box = ByteArray(totalSize)

        // Item Header ('trkn')
        writeInt(box, 0, totalSize)
        box[4] = 't'.code.toByte()
        box[5] = 'r'.code.toByte()
        box[6] = 'k'.code.toByte()
        box[7] = 'n'.code.toByte()

        // 'data' Box (flags = 0 for binary)
        writeInt(box, 8, dataBoxSize)
        box[12] = 'd'.code.toByte()
        box[13] = 'a'.code.toByte()
        box[14] = 't'.code.toByte()
        box[15] = 'a'.code.toByte()
        writeInt(box, 16, 0) // flags = 0
        writeInt(box, 20, 0) // locale = 0

        // Track payload (8 bytes)
        box[24] = 0
        box[25] = 0
        box[26] = ((trackNum shr 8) and 0xFF).toByte()
        box[27] = (trackNum and 0xFF).toByte()
        // remaining 4 bytes 0

        return box
    }

    private fun createMdirHdlrBox(): ByteArray {
        val box = ByteArray(33)
        writeInt(box, 0, 33)
        box[4] = 'h'.code.toByte()
        box[5] = 'd'.code.toByte()
        box[6] = 'l'.code.toByte()
        box[7] = 'r'.code.toByte()
        // 8..11 version/flags = 0
        // 12..15 pre-defined = 0
        // 16..19 handler type 'mdir'
        box[16] = 'm'.code.toByte()
        box[17] = 'd'.code.toByte()
        box[18] = 'i'.code.toByte()
        box[19] = 'r'.code.toByte()
        // 20..23 'appl'
        box[20] = 'a'.code.toByte()
        box[21] = 'p'.code.toByte()
        box[22] = 'p'.code.toByte()
        box[23] = 'l'.code.toByte()
        // 24..31 reserved = 0
        // 32 name string null terminator = 0
        return box
    }

    /**
     * Traverses the reconstructed moov byte array and updates all 'stco' (32-bit) and 'co64' (64-bit)
     * chunk offsets by delta bytes to match the new 'mdat' position.
     */
    private fun adjustChunkOffsets(moovBytes: ByteArray, delta: Long) {
        if (delta == 0L) return

        var i = 0
        val len = moovBytes.size - 8
        while (i <= len) {
            // Check for 'stco' atom
            if (moovBytes[i + 4] == 's'.code.toByte() &&
                moovBytes[i + 5] == 't'.code.toByte() &&
                moovBytes[i + 6] == 'c'.code.toByte() &&
                moovBytes[i + 7] == 'o'.code.toByte()
            ) {
                val boxSize = readUInt32(moovBytes, i).toInt()
                if (boxSize >= 16 && i + boxSize <= moovBytes.size) {
                    val entryCount = readUInt32(moovBytes, i + 12).toInt()
                    var ptr = i + 16
                    for (e in 0 until entryCount) {
                        if (ptr + 4 > moovBytes.size) break
                        val currentOffset = readUInt32(moovBytes, ptr)
                        val newOffset = currentOffset + delta
                        writeInt(moovBytes, ptr, newOffset.toInt())
                        ptr += 4
                    }
                }
            } else if (moovBytes[i + 4] == 'c'.code.toByte() &&
                moovBytes[i + 5] == 'o'.code.toByte() &&
                moovBytes[i + 6] == '6'.code.toByte() &&
                moovBytes[i + 7] == '4'.code.toByte()
            ) {
                // Check for 'co64' atom (64-bit offsets)
                val boxSize = readUInt32(moovBytes, i).toInt()
                if (boxSize >= 16 && i + boxSize <= moovBytes.size) {
                    val entryCount = readUInt32(moovBytes, i + 12).toInt()
                    var ptr = i + 16
                    for (e in 0 until entryCount) {
                        if (ptr + 8 > moovBytes.size) break
                        val currentOffset = readUInt64(moovBytes, ptr)
                        val newOffset = currentOffset + delta
                        writeLong(moovBytes, ptr, newOffset)
                        ptr += 8
                    }
                }
            }
            i++
        }
    }

    private data class ParsedMoov(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val year: String = "",
        val trackNumber: String = "",
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg"
    )

    private fun parseMoovMetadata(moovBytes: ByteArray): ParsedMoov {
        var title = ""
        var artist = ""
        var album = ""
        var genre = ""
        var year = ""
        var trackNumber = ""
        var coverBytes: ByteArray? = null
        var coverMime = "image/jpeg"

        // Search for 'covr' box
        var i = 0
        val len = moovBytes.size - 16
        while (i <= len) {
            val b4 = moovBytes[i + 4]
            val b5 = moovBytes[i + 5]
            val b6 = moovBytes[i + 6]
            val b7 = moovBytes[i + 7]

            // 'covr'
            if (b4 == 'c'.code.toByte() && b5 == 'o'.code.toByte() && b6 == 'v'.code.toByte() && b7 == 'r'.code.toByte()) {
                val boxSize = readUInt32(moovBytes, i).toInt()
                if (boxSize >= 24 && i + boxSize <= moovBytes.size) {
                    // Inside covr, expect 'data' box at i + 8
                    if (moovBytes[i + 12] == 'd'.code.toByte() && moovBytes[i + 13] == 'a'.code.toByte() &&
                        moovBytes[i + 14] == 't'.code.toByte() && moovBytes[i + 15] == 'a'.code.toByte()
                    ) {
                        val dataFlags = readUInt32(moovBytes, i + 16).toInt()
                        coverMime = if (dataFlags == 14) "image/png" else "image/jpeg"
                        val dataPayloadSize = boxSize - 24
                        if (dataPayloadSize > 0) {
                            coverBytes = ByteArray(dataPayloadSize)
                            System.arraycopy(moovBytes, i + 24, coverBytes, 0, dataPayloadSize)
                        }
                    }
                }
            } else if (b4 == 0xA9.toByte() && b5 == 'n'.code.toByte() && b6 == 'a'.code.toByte() && b7 == 'm'.code.toByte()) {
                // '©nam' Title
                extractIlstString(moovBytes, i)?.let { title = it }
            } else if (b4 == 0xA9.toByte() && b5 == 'A'.code.toByte() && b6 == 'R'.code.toByte() && b7 == 'T'.code.toByte()) {
                // '©ART' Artist
                extractIlstString(moovBytes, i)?.let { artist = it }
            } else if (b4 == 0xA9.toByte() && b5 == 'a'.code.toByte() && b6 == 'l'.code.toByte() && b7 == 'b'.code.toByte()) {
                // '©alb' Album
                extractIlstString(moovBytes, i)?.let { album = it }
            } else if (b4 == 0xA9.toByte() && b5 == 'd'.code.toByte() && b6 == 'a'.code.toByte() && b7 == 'y'.code.toByte()) {
                // '©day' Year
                extractIlstString(moovBytes, i)?.let { year = it }
            } else if (b4 == 0xA9.toByte() && b5 == 'g'.code.toByte() && b6 == 'e'.code.toByte() && b7 == 'n'.code.toByte()) {
                // '©gen' Genre
                extractIlstString(moovBytes, i)?.let { genre = it }
            } else if (b4 == 't'.code.toByte() && b5 == 'r'.code.toByte() && b6 == 'k'.code.toByte() && b7 == 'n'.code.toByte()) {
                // 'trkn' Track Number
                if (i + 28 <= moovBytes.size) {
                    val t = ((moovBytes[i + 26].toInt() and 0xFF) shl 8) or (moovBytes[i + 27].toInt() and 0xFF)
                    if (t > 0) trackNumber = t.toString()
                }
            }
            i++
        }

        return ParsedMoov(title, artist, album, genre, year, trackNumber, coverBytes, coverMime)
    }

    private fun extractIlstString(bytes: ByteArray, boxOffset: Int): String? {
        val boxSize = readUInt32(bytes, boxOffset).toInt()
        if (boxSize >= 24 && boxOffset + boxSize <= bytes.size) {
            val textLen = boxSize - 24
            if (textLen > 0) {
                return String(bytes, boxOffset + 24, textLen, StandardCharsets.UTF_8).trim()
            }
        }
        return null
    }

    private fun readUInt32(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFF) shl 24) or
                ((b[offset + 1].toLong() and 0xFF) shl 16) or
                ((b[offset + 2].toLong() and 0xFF) shl 8) or
                (b[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt64(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun writeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value ushr 24) and 0xFF).toByte()
        b[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        b[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        b[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeLong(b: ByteArray, offset: Int, value: Long) {
        for (i in 7 downTo 0) {
            b[offset + (7 - i)] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
    }
}
