package com.example

import com.example.mp3.AudioFormat
import com.example.mp3.AudioMetadataEngine
import com.example.mp3.Mp3Metadata
import com.example.mp3.WavMetadataEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class WavMetadataEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Generates a valid WAV PCM 16-bit stereo 44,100 Hz file.
     * Deliberately injects 0xFF 0xFB 0x90 0x64 bytes inside the PCM samples to verify
     * that MPEG sync false-positives never corrupt or misclassify WAV audio.
     */
    private fun createPcmWavFile(destFile: File, durationSeconds: Int = 1, includeOddDataLength: Boolean = false): ByteArray {
        val sampleRate = 44100
        val channels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8) // 176,400 bytes/sec
        val blockAlign = channels * (bitsPerSample / 8) // 4 bytes
        val dataSize = byteRate * durationSeconds + (if (includeOddDataLength) 1 else 0)

        val pcmSamples = ByteArray(dataSize) { idx -> ((idx * 31) % 256).toByte() }
        // Inject deceptive MPEG-like sync bytes inside PCM audio payload
        if (pcmSamples.size > 100) {
            pcmSamples[40] = 0xFF.toByte()
            pcmSamples[41] = 0xFB.toByte()
            pcmSamples[42] = 0x90.toByte()
            pcmSamples[43] = 0x64.toByte()
        }

        val alignedDataSize = dataSize + (dataSize and 1)
        val riffSize = 4L + (8L + 16L) + (8L + alignedDataSize.toLong())

        FileOutputStream(destFile).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
            out.write(intToLE(riffSize.toInt()))
            out.write("WAVE".toByteArray(StandardCharsets.US_ASCII))

            // fmt chunk (16 bytes)
            out.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
            out.write(intToLE(16))
            out.write(shortToLE(1)) // PCM = 1
            out.write(shortToLE(channels))
            out.write(intToLE(sampleRate))
            out.write(intToLE(byteRate))
            out.write(shortToLE(blockAlign))
            out.write(shortToLE(bitsPerSample))

            // data chunk
            out.write("data".toByteArray(StandardCharsets.US_ASCII))
            out.write(intToLE(dataSize))
            out.write(pcmSamples)
            if ((dataSize and 1) != 0) {
                out.write(0)
            }
        }
        return pcmSamples
    }

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

    private fun intToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun shortToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte()
    )

    private fun readUInt32LE(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFF) or
            ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or
            ((b[offset + 3].toLong() and 0xFF) shl 24)
    }

    /**
     * Test 1: WAV PCM 16-bit stereo 44.1 kHz file named ".mp3" ("Gila Lagi - Lish.mp3")
     * MUST be detected strictly by RIFF/WAVE signature as AudioFormat.WAV, not MP3.
     */
    @Test
    fun testWavWithFakeMp3ExtensionDetectedAsWav() {
        val deceptiveWavFile = tempFolder.newFile("Gila Lagi - Lish.mp3")
        createPcmWavFile(deceptiveWavFile, durationSeconds = 1)

        val detected = AudioFormat.detect(deceptiveWavFile)
        assertEquals("RIFF/WAVE file named .mp3 MUST be detected as WAV!", AudioFormat.WAV, detected)
        assertEquals("wav", detected.extension)
        assertEquals("audio/x-wav", detected.mimeType)
    }

    /**
     * Test 2: Adding JPEG cover art & metadata to WAV preserves PCM audio bit-for-bit,
     * preserves exact duration, writes valid RIFF chunk sizes, LIST/INFO, and id3 chunk.
     */
    @Test
    fun testAddJpegCoverToWavPreservesPcmAudioAndDuration() {
        val sourceWav = tempFolder.newFile("Gila Lagi - Lish.mp3")
        val originalPcm = createPcmWavFile(sourceWav, durationSeconds = 2)

        val sourceMeta = WavMetadataEngine.extractMetadata(null, sourceWav)
        assertEquals(2000L, sourceMeta.durationMs)
        assertEquals(AudioFormat.WAV, sourceMeta.audioFormat)

        val coverJpeg = sampleJpegBytes()
        val updatedMeta = sourceMeta.copy(
            title = "Gila Lagi",
            artist = "Lish",
            album = "Rekaman Studio",
            genre = "Acoustic",
            year = "2026",
            trackNumber = "1",
            coverImageBytes = coverJpeg,
            coverMimeType = "image/jpeg"
        )

        val exportedWav = tempFolder.newFile("Gila Lagi - Lish.wav")
        FileOutputStream(exportedWav).use { out ->
            AudioMetadataEngine.exportAudio(sourceWav, updatedMeta, out)
        }

        // 1. Validate exported file format & RIFF header
        assertEquals(AudioFormat.WAV, AudioFormat.detect(exportedWav))
        RandomAccessFile(exportedWav, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            assertEquals("RIFF", String(header, 0, 4, StandardCharsets.US_ASCII))
            assertEquals("WAVE", String(header, 8, 4, StandardCharsets.US_ASCII))
            val riffSize = readUInt32LE(header, 4)
            assertEquals("RIFF size must equal file.length() - 8", exportedWav.length() - 8L, riffSize)

            // Walk all chunks and verify fmt, data, LIST INFO, and id3
            var offset = 12L
            val foundChunkIds = mutableListOf<String>()
            var extractedPcm: ByteArray? = null

            while (offset <= exportedWav.length() - 8L) {
                raf.seek(offset)
                val ch = ByteArray(8)
                raf.readFully(ch)
                val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
                val sz = readUInt32LE(ch, 4)
                foundChunkIds.add(id)

                if (id == "data") {
                    extractedPcm = ByteArray(sz.toInt())
                    raf.readFully(extractedPcm)
                }

                offset += 8L + sz + (sz and 1L)
            }

            assertEquals("All chunks must align exactly to EOF", exportedWav.length(), offset)
            assertTrue("Must contain fmt chunk", foundChunkIds.contains("fmt "))
            assertTrue("Must contain data chunk", foundChunkIds.contains("data"))
            assertTrue("Must contain LIST chunk", foundChunkIds.contains("LIST"))
            assertTrue("Must contain id3 chunk", foundChunkIds.contains("id3 "))
            assertNotNull(extractedPcm)
            assertArrayEquals("PCM audio stream MUST be bit-for-bit identical!", originalPcm, extractedPcm)
        }

        // 2. Read metadata back from exported WAV
        val readBack = WavMetadataEngine.extractMetadata(null, exportedWav)
        assertEquals("Gila Lagi", readBack.title)
        assertEquals("Lish", readBack.artist)
        assertEquals("Rekaman Studio", readBack.album)
        assertEquals("Acoustic", readBack.genre)
        assertEquals("2026", readBack.year)
        assertEquals("1", readBack.trackNumber)
        assertEquals("Duration MUST remain exact 2000ms", 2000L, readBack.durationMs)
        assertNotNull("Cover art MUST be readable from exported WAV", readBack.coverImageBytes)
        assertArrayEquals("Cover art JPEG bytes must match exactly", coverJpeg, readBack.coverImageBytes)
        assertEquals("image/jpeg", readBack.coverMimeType)
    }

    /**
     * Test 3: Replacing cover art (JPEG -> PNG) and removing cover art in WAV without duplicating chunks.
     */
    @Test
    fun testReplaceAndRemoveCoverArtInWav() {
        val rawWav = tempFolder.newFile("source_replace.wav")
        val originalPcm = createPcmWavFile(rawWav, durationSeconds = 1, includeOddDataLength = true)

        val metaJpeg = Mp3Metadata(
            title = "Track WAV",
            artist = "Tester",
            album = "Album 1",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )
        val wavWithJpeg = tempFolder.newFile("with_jpeg.wav")
        FileOutputStream(wavWithJpeg).use { AudioMetadataEngine.exportAudio(rawWav, metaJpeg, it) }

        // Replace with PNG cover
        val pngBytes = samplePngBytes()
        val metaPng = metaJpeg.copy(
            title = "Track WAV Edited",
            coverImageBytes = pngBytes,
            coverMimeType = "image/png"
        )
        val wavWithPng = tempFolder.newFile("with_png.wav")
        FileOutputStream(wavWithPng).use { AudioMetadataEngine.exportAudio(wavWithJpeg, metaPng, it) }

        val readPng = WavMetadataEngine.extractMetadata(null, wavWithPng)
        assertEquals("Track WAV Edited", readPng.title)
        assertArrayEquals(pngBytes, readPng.coverImageBytes)
        assertEquals("image/png", readPng.coverMimeType)

        // Ensure there is only ONE 'id3 ' chunk and ONE 'LIST' chunk (no stacking!)
        var id3ChunkCount = 0
        var listChunkCount = 0
        RandomAccessFile(wavWithPng, "r").use { raf ->
            var offset = 12L
            val ch = ByteArray(8)
            while (offset <= wavWithPng.length() - 8L) {
                raf.seek(offset)
                raf.readFully(ch)
                val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
                val sz = readUInt32LE(ch, 4)
                if (id == "id3 ") id3ChunkCount++
                if (id == "LIST") listChunkCount++
                offset += 8L + sz + (sz and 1L)
            }
            assertEquals("RIFF chunk walk must terminate cleanly at EOF", wavWithPng.length(), offset)
        }
        assertEquals("Must not duplicate id3 chunks", 1, id3ChunkCount)
        assertEquals("Must not duplicate LIST chunks", 1, listChunkCount)

        // Now remove cover art
        val metaNoCover = readPng.copy(coverImageBytes = null, coverBitmap = null)
        val wavNoCover = tempFolder.newFile("no_cover.wav")
        FileOutputStream(wavNoCover).use { AudioMetadataEngine.exportAudio(wavWithPng, metaNoCover, it) }

        val readNoCover = WavMetadataEngine.extractMetadata(null, wavNoCover)
        assertEquals("Track WAV Edited", readNoCover.title)
        assertNull("Cover art should be removed", readNoCover.coverImageBytes)

        // Verify odd-length PCM data chunk stayed bit-identical through 3 consecutive exports
        RandomAccessFile(wavNoCover, "r").use { raf ->
            var offset = 12L
            val ch = ByteArray(8)
            while (offset <= wavNoCover.length() - 8L) {
                raf.seek(offset)
                raf.readFully(ch)
                val id = String(ch, 0, 4, StandardCharsets.US_ASCII)
                val sz = readUInt32LE(ch, 4)
                if (id == "data") {
                    val pcm = ByteArray(sz.toInt())
                    raf.readFully(pcm)
                    assertArrayEquals("Odd-length PCM audio stream must remain 100% intact", originalPcm, pcm)
                }
                offset += 8L + sz + (sz and 1L)
            }
        }
    }

    /**
     * Test 4: Rescues a WAV file that had an ID3v2 header mistakenly prepended before RIFF.
     */
    @Test
    fun testRescueWavWithMistakenPrependedId3Header() {
        val cleanWav = tempFolder.newFile("clean_for_broken.wav")
        val originalPcm = createPcmWavFile(cleanWav, durationSeconds = 1)

        // Simulate broken file: [ID3v2 tag] + [RIFF...WAVE...]
        val brokenFile = tempFolder.newFile("broken_id3_wrapped.mp3")
        val fakeId3 = com.example.mp3.Mp3Id3Engine.buildId3v23Tag(Mp3Metadata(title = "Old Broken"))
        FileOutputStream(brokenFile).use { out ->
            out.write(fakeId3)
            out.write(cleanWav.readBytes())
        }

        // Signature detection must look past ID3 and identify WAV!
        assertEquals(AudioFormat.WAV, AudioFormat.detect(brokenFile))

        val rescuedFile = tempFolder.newFile("rescued.wav")
        val newMeta = Mp3Metadata(
            title = "Gila Lagi",
            artist = "Lish",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )
        FileOutputStream(rescuedFile).use { out ->
            AudioMetadataEngine.exportAudio(brokenFile, newMeta, out)
        }

        // Rescued file must start with RIFF, not ID3!
        RandomAccessFile(rescuedFile, "r").use { raf ->
            val lead = ByteArray(12)
            raf.readFully(lead)
            assertEquals("RIFF", String(lead, 0, 4, StandardCharsets.US_ASCII))
            assertEquals("WAVE", String(lead, 8, 4, StandardCharsets.US_ASCII))
            assertEquals(rescuedFile.length() - 8L, readUInt32LE(lead, 4))
        }

        val readRescued = WavMetadataEngine.extractMetadata(null, rescuedFile)
        assertEquals("Gila Lagi", readRescued.title)
        assertEquals("Lish", readRescued.artist)
        assertArrayEquals(sampleJpegBytes(), readRescued.coverImageBytes)
        assertEquals(1000L, readRescued.durationMs)
        assertTrue(originalPcm.isNotEmpty())
    }
}
