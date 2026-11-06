package com.example

import com.example.mp3.AudioFormat
import com.example.mp3.AudioMetadataEngine
import com.example.mp3.Mp3Metadata
import com.example.mp3.Mp4MetadataEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class Mp4MetadataEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleJpegBytes(): ByteArray {
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x4A.toByte(), 0x46.toByte(),
            0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xD9.toByte()
        )
    }

    private fun samplePngBytes(): ByteArray {
        return byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(),
            0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(),
            0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(),
            0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
    }

    /**
     * Helper to create a valid ISO-14496-12 MP4/M4A file with ftyp, moov (containing stco), and mdat.
     */
    private fun createSyntheticMp4File(destFile: File, rawAudioSamples: ByteArray): Long {
        val out = FileOutputStream(destFile)

        // 1. ftyp box (32 bytes)
        val ftyp = ByteArray(32)
        writeInt(ftyp, 0, 32)
        System.arraycopy("ftyp".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 4, 4)
        System.arraycopy("mp42".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 8, 4) // major brand
        writeInt(ftyp, 12, 0) // minor version
        System.arraycopy("isom".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 16, 4)
        System.arraycopy("mp42".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 20, 4)
        System.arraycopy("M4A ".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 24, 4)
        System.arraycopy("mp41".toByteArray(StandardCharsets.US_ASCII), 0, ftyp, 28, 4)
        out.write(ftyp)

        // 2. Build moov box
        // To accurately calculate initial stco offsets, we need the initial moov size.
        // Let's create mvhd (108 bytes)
        val mvhd = ByteArray(108)
        writeInt(mvhd, 0, 108)
        System.arraycopy("mvhd".toByteArray(StandardCharsets.US_ASCII), 0, mvhd, 4, 4)
        writeInt(mvhd, 12, 44100) // timescale
        writeInt(mvhd, 16, 88200) // duration = 2.0s

        // Build trak -> mdia -> minf -> stbl
        // stsd (sound description 'mp4a')
        val stsd = ByteArray(36)
        writeInt(stsd, 0, 36)
        System.arraycopy("stsd".toByteArray(StandardCharsets.US_ASCII), 0, stsd, 4, 4)
        writeInt(stsd, 8, 0)
        writeInt(stsd, 12, 1) // 1 description
        writeInt(stsd, 16, 20)
        System.arraycopy("mp4a".toByteArray(StandardCharsets.US_ASCII), 0, stsd, 20, 4)

        // stts
        val stts = ByteArray(24)
        writeInt(stts, 0, 24)
        System.arraycopy("stts".toByteArray(StandardCharsets.US_ASCII), 0, stts, 4, 4)
        writeInt(stts, 12, 1)
        writeInt(stts, 16, 86)
        writeInt(stts, 20, 1024)

        // stsc
        val stsc = ByteArray(28)
        writeInt(stsc, 0, 28)
        System.arraycopy("stsc".toByteArray(StandardCharsets.US_ASCII), 0, stsc, 4, 4)
        writeInt(stsc, 12, 1)
        writeInt(stsc, 16, 1) // first chunk
        writeInt(stsc, 20, 1) // samples per chunk
        writeInt(stsc, 24, 1) // sample desc index

        // stsz
        val stsz = ByteArray(20)
        writeInt(stsz, 0, 20)
        System.arraycopy("stsz".toByteArray(StandardCharsets.US_ASCII), 0, stsz, 4, 4)
        writeInt(stsz, 12, rawAudioSamples.size) // uniform size or total
        writeInt(stsz, 16, 1)

        // stco (32-bit chunk offset table)
        // 1 chunk offset pointing to mdat payload start (32 ftyp + 485 moov + 8 mdat header = 525)
        val stco = ByteArray(20)
        writeInt(stco, 0, 20)
        System.arraycopy("stco".toByteArray(StandardCharsets.US_ASCII), 0, stco, 4, 4)
        writeInt(stco, 12, 1) // 1 chunk
        writeInt(stco, 16, 525) // initial chunk offset pointing to mdat payload start

        // Assemble stbl
        val stblPayload = ByteArrayOutputStream()
        stblPayload.write(stsd)
        stblPayload.write(stts)
        stblPayload.write(stsc)
        stblPayload.write(stsz)
        stblPayload.write(stco)
        val stblBytes = stblPayload.toByteArray()
        val stbl = wrapBox("stbl", stblBytes)

        // minf
        val smhd = ByteArray(16).apply {
            writeInt(this, 0, 16)
            System.arraycopy("smhd".toByteArray(StandardCharsets.US_ASCII), 0, this, 4, 4)
        }
        val dinf = ByteArray(36).apply {
            writeInt(this, 0, 36)
            System.arraycopy("dinf".toByteArray(StandardCharsets.US_ASCII), 0, this, 4, 4)
        }
        val minfPayload = ByteArrayOutputStream().apply {
            write(smhd)
            write(dinf)
            write(stbl)
        }
        val minf = wrapBox("minf", minfPayload.toByteArray())

        // mdia
        val mdhd = ByteArray(32).apply {
            writeInt(this, 0, 32)
            System.arraycopy("mdhd".toByteArray(StandardCharsets.US_ASCII), 0, this, 4, 4)
            writeInt(this, 12, 44100)
            writeInt(this, 16, 88200)
        }
        val hdlr = ByteArray(33).apply {
            writeInt(this, 0, 33)
            System.arraycopy("hdlr".toByteArray(StandardCharsets.US_ASCII), 0, this, 4, 4)
            this[16] = 's'.code.toByte()
            this[17] = 'o'.code.toByte()
            this[18] = 'u'.code.toByte()
            this[19] = 'n'.code.toByte()
        }
        val mdiaPayload = ByteArrayOutputStream().apply {
            write(mdhd)
            write(hdlr)
            write(minf)
        }
        val mdia = wrapBox("mdia", mdiaPayload.toByteArray())

        // trak
        val tkhd = ByteArray(92).apply {
            writeInt(this, 0, 92)
            System.arraycopy("tkhd".toByteArray(StandardCharsets.US_ASCII), 0, this, 4, 4)
            writeInt(this, 12, 1) // track ID
            writeInt(this, 20, 88200)
        }
        val trakPayload = ByteArrayOutputStream().apply {
            write(tkhd)
            write(mdia)
        }
        val trak = wrapBox("trak", trakPayload.toByteArray())

        // moov
        val moovPayload = ByteArrayOutputStream().apply {
            write(mvhd)
            write(trak)
        }
        val moov = wrapBox("moov", moovPayload.toByteArray())

        // Compute where mdat audio payload will start: ftyp.size + moov.size + 8 (mdat header)
        val initialMdatPayloadOffset = 32L + moov.size + 8L

        // Update the stco chunk offset in moov before writing
        updateFirstStcoOffset(moov, initialMdatPayloadOffset.toInt())
        out.write(moov)

        // 3. mdat box
        val mdatHeader = ByteArray(8)
        writeInt(mdatHeader, 0, 8 + rawAudioSamples.size)
        System.arraycopy("mdat".toByteArray(StandardCharsets.US_ASCII), 0, mdatHeader, 4, 4)
        out.write(mdatHeader)
        out.write(rawAudioSamples)

        out.close()
        return initialMdatPayloadOffset
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val totalSize = 8 + payload.size
        val box = ByteArray(totalSize)
        writeInt(box, 0, totalSize)
        System.arraycopy(type.toByteArray(StandardCharsets.US_ASCII), 0, box, 4, 4)
        System.arraycopy(payload, 0, box, 8, payload.size)
        return box
    }

    private fun writeInt(b: ByteArray, offset: Int, value: Int) {
        b[offset] = ((value ushr 24) and 0xFF).toByte()
        b[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        b[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        b[offset + 3] = (value and 0xFF).toByte()
    }

    private fun updateFirstStcoOffset(moovBytes: ByteArray, offsetValue: Int) {
        for (i in 0 until moovBytes.size - 20) {
            if (moovBytes[i + 4] == 's'.code.toByte() &&
                moovBytes[i + 5] == 't'.code.toByte() &&
                moovBytes[i + 6] == 'c'.code.toByte() &&
                moovBytes[i + 7] == 'o'.code.toByte()
            ) {
                // write to first entry at i + 16
                writeInt(moovBytes, i + 16, offsetValue)
                return
            }
        }
    }

    /**
     * Test 1: Format Detection strictly by magic bytes, not file extension.
     */
    @Test
    fun testFormatDetectionBySignature() {
        val dummyAacSamples = ByteArray(500) { 0x21 }

        // MP4 file deceptively named "song.mp3"
        val deceptiveFile = tempFolder.newFile("deceptive_song.mp3")
        createSyntheticMp4File(deceptiveFile, dummyAacSamples)

        val detectedFormat = AudioFormat.detect(deceptiveFile)
        assertEquals("File with 'ftypmp42' signature must be detected as MP4_AAC even if named .mp3!",
            AudioFormat.MP4_AAC, detectedFormat)
        assertEquals("m4a", detectedFormat.extension)
        assertEquals("audio/mp4", detectedFormat.mimeType)

        // Real MP3 file starting with ID3
        val realMp3File = tempFolder.newFile("real.mp3")
        FileOutputStream(realMp3File).use {
            it.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0))
            it.write(ByteArray(100))
        }
        val mp3Format = AudioFormat.detect(realMp3File)
        assertEquals(AudioFormat.MP3, mp3Format)
    }

    /**
     * Test 2: Adding Cover Art to an MP4/M4A file without changing audio.
     */
    @Test
    fun testAddCoverArtToMp4() {
        val audioPayload = ByteArray(1500) { (it % 256).toByte() }
        val sourceMp4 = tempFolder.newFile("clean_source.m4a")
        createSyntheticMp4File(sourceMp4, audioPayload)

        val jpegCover = sampleJpegBytes()
        val meta = Mp3Metadata(
            title = "Lagu AAC Indah",
            artist = "Penyanyi Digital",
            album = "Album AAC Vol 1",
            genre = "Acoustic",
            year = "2024",
            trackNumber = "3",
            coverImageBytes = jpegCover,
            coverMimeType = "image/jpeg"
        )

        val exportedMp4 = tempFolder.newFile("exported_with_cover.m4a")
        FileOutputStream(exportedMp4).use { out ->
            AudioMetadataEngine.exportAudio(sourceMp4, meta, out)
        }

        assertTrue("Exported file must exist", exportedMp4.exists())
        assertTrue("Exported file must be larger due to cover art", exportedMp4.length() > sourceMp4.length())

        // 1. Verify that NO ID3v2 tag was written at byte 0
        RandomAccessFile(exportedMp4, "r").use { raf ->
            val lead = ByteArray(8)
            raf.readFully(lead)
            assertEquals("Byte 4..7 must still be 'ftyp'!", "ftyp", String(lead, 4, 4, StandardCharsets.US_ASCII))
            assertFalse("Must NOT begin with ID3!", lead[0] == 'I'.code.toByte() && lead[1] == 'D'.code.toByte() && lead[2] == '3'.code.toByte())
        }

        // 2. Read back metadata using native MP4 parser
        val readBack = Mp4MetadataEngine.extractMetadata(null, exportedMp4)
        assertEquals("Lagu AAC Indah", readBack.title)
        assertEquals("Penyanyi Digital", readBack.artist)
        assertEquals("Album AAC Vol 1", readBack.album)
        assertEquals("Acoustic", readBack.genre)
        assertEquals("2024", readBack.year)
        assertEquals("3", readBack.trackNumber)
        assertNotNull("Cover art must be present", readBack.coverImageBytes)
        assertArrayEquals("Cover art bytes must be identical to JPEG", jpegCover, readBack.coverImageBytes)
        assertEquals("image/jpeg", readBack.coverMimeType)

        // 3. Verify that raw audio stream in mdat is bit-for-bit identical to source
        RandomAccessFile(exportedMp4, "r").use { raf ->
            // Find mdat
            var mdatOffset = -1L
            var mdatPayloadSize = -1L
            var offset = 0L
            while (offset < exportedMp4.length() - 8) {
                raf.seek(offset)
                val h = ByteArray(8)
                raf.readFully(h)
                val sz = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
                val type = String(h, 4, 4, StandardCharsets.US_ASCII)
                if (type == "mdat") {
                    mdatOffset = offset + 8
                    mdatPayloadSize = sz - 8
                    break
                }
                offset += sz
            }

            assertTrue("mdat box must be found", mdatOffset > 0)
            assertEquals("mdat payload size must match audio payload exactly", audioPayload.size.toLong(), mdatPayloadSize)

            val extractedAudio = ByteArray(audioPayload.size)
            raf.seek(mdatOffset)
            raf.readFully(extractedAudio)
            assertArrayEquals("Extracted AAC audio payload must be bit-for-bit identical!", audioPayload, extractedAudio)
        }
    }

    /**
     * Test 3: Replacing Cover Art in MP4 (e.g. from JPEG to PNG).
     */
    @Test
    fun testReplaceCoverArtInMp4() {
        val audioPayload = ByteArray(1000) { (it * 7 % 256).toByte() }
        val initialFile = tempFolder.newFile("initial_mp4.m4a")
        createSyntheticMp4File(initialFile, audioPayload)

        val metaOld = Mp3Metadata(
            title = "Track Original",
            artist = "Artist Original",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )
        val withJpegFile = tempFolder.newFile("with_jpeg.m4a")
        FileOutputStream(withJpegFile).use { out ->
            AudioMetadataEngine.exportAudio(initialFile, metaOld, out)
        }

        // Now replace with PNG cover
        val pngCover = samplePngBytes()
        val metaNew = metaOld.copy(
            title = "Track Replaced",
            coverImageBytes = pngCover,
            coverMimeType = "image/png"
        )
        val withPngFile = tempFolder.newFile("with_png.m4a")
        FileOutputStream(withPngFile).use { out ->
            AudioMetadataEngine.exportAudio(withJpegFile, metaNew, out)
        }

        val readBack = Mp4MetadataEngine.extractMetadata(null, withPngFile)
        assertEquals("Track Replaced", readBack.title)
        assertNotNull(readBack.coverImageBytes)
        assertArrayEquals("Must have PNG cover bytes", pngCover, readBack.coverImageBytes)
        assertEquals("image/png", readBack.coverMimeType)

        // Verify audio payload still bit-identical
        RandomAccessFile(withPngFile, "r").use { raf ->
            var offset = 0L
            var mdatPayloadOffset = -1L
            while (offset < withPngFile.length() - 8) {
                raf.seek(offset)
                val h = ByteArray(8)
                raf.readFully(h)
                val sz = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
                val type = String(h, 4, 4, StandardCharsets.US_ASCII)
                if (type == "mdat") {
                    mdatPayloadOffset = offset + 8
                    break
                }
                offset += sz
            }
            val extractedAudio = ByteArray(audioPayload.size)
            raf.seek(mdatPayloadOffset)
            raf.readFully(extractedAudio)
            assertArrayEquals("Audio stream must remain 100% bit-exact across cover replacements", audioPayload, extractedAudio)
        }
    }

    /**
     * Test 4: Verifies that stco chunk offsets are correctly adjusted by delta.
     */
    @Test
    fun testStcoOffsetsAdjustedByDelta() {
        val audioPayload = ByteArray(800) { 0x55 }
        val rawMp4 = tempFolder.newFile("raw_stco_test.m4a")
        createSyntheticMp4File(rawMp4, audioPayload)

        val meta = Mp3Metadata(
            title = "Delta Check",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )
        val exportedFile = tempFolder.newFile("exported_stco.m4a")
        FileOutputStream(exportedFile).use { out ->
            AudioMetadataEngine.exportAudio(rawMp4, meta, out)
        }

        // Parse exported file: find mdat offset and check stco offset value
        RandomAccessFile(exportedFile, "r").use { raf ->
            var offset = 0L
            var mdatOffset = -1L
            var stcoOffsetValue = -1L

            while (offset < exportedFile.length() - 8) {
                raf.seek(offset)
                val h = ByteArray(8)
                raf.readFully(h)
                val sz = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
                val type = String(h, 4, 4, StandardCharsets.US_ASCII)

                if (type == "moov") {
                    // search for stco in moov
                    val moovBytes = ByteArray(sz.toInt())
                    raf.seek(offset)
                    raf.readFully(moovBytes)
                    for (i in 0 until moovBytes.size - 20) {
                        if (moovBytes[i + 4] == 's'.code.toByte() &&
                            moovBytes[i + 5] == 't'.code.toByte() &&
                            moovBytes[i + 6] == 'c'.code.toByte() &&
                            moovBytes[i + 7] == 'o'.code.toByte()
                        ) {
                            stcoOffsetValue = ((moovBytes[i + 16].toLong() and 0xFF) shl 24) or
                                    ((moovBytes[i + 17].toLong() and 0xFF) shl 16) or
                                    ((moovBytes[i + 18].toLong() and 0xFF) shl 8) or
                                    (moovBytes[i + 19].toLong() and 0xFF)
                        }
                    }
                } else if (type == "mdat") {
                    mdatOffset = offset + 8 // payload start
                }

                offset += sz
            }

            assertTrue("stco offset value must match actual mdat audio payload start position in the file", stcoOffsetValue > 0)
            assertEquals("stco offset must point precisely to mdat audio payload offset!", mdatOffset, stcoOffsetValue)
        }
    }
}
