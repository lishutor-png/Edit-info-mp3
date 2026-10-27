package com.example

import com.example.mp3.Mp3Id3Engine
import com.example.mp3.Mp3Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testMpegFrameSizeCalculation() {
        // Test standard 128 kbps, 44100 Hz, MPEG-1 Layer 3 frame header (no padding)
        // 0xFF 0xFB 0x90 0x64
        // b0 = 0xFF
        // b1 = 0xFB -> versionId = 3 (MPEG-1), layerId = 1 (Layer III), protection = 1
        // b2 = 0x90 -> bitrateIdx = 9 (128 kbps), sampleRateIdx = 0 (44100 Hz), padding = 0
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        val frameSize = Mp3Id3Engine.getMpegFrameSize(header, 0, 4)
        // Expected size = (144 * 128000) / 44100 + 0 = 417 bytes
        assertEquals(417, frameSize)
    }

    @Test
    fun testId3v23TagBuilder() {
        val meta = Mp3Metadata(
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            genre = "Pop",
            year = "2024",
            trackNumber = "1"
        )
        val tagBytes = Mp3Id3Engine.buildId3v23Tag(meta)
        assertTrue(tagBytes.size > 10)
        assertEquals('I'.code.toByte(), tagBytes[0])
        assertEquals('D'.code.toByte(), tagBytes[1])
        assertEquals('3'.code.toByte(), tagBytes[2])
        assertEquals(0x03.toByte(), tagBytes[3]) // v2.3
    }

    @Test
    fun testId3v1TagBuilder() {
        val meta = Mp3Metadata(
            title = "Song A",
            artist = "Artist B",
            album = "Album C",
            year = "2024",
            trackNumber = "5"
        )
        val v1Bytes = Mp3Id3Engine.buildId3v1Tag(meta)
        assertEquals(128, v1Bytes.size)
        assertEquals('T'.code.toByte(), v1Bytes[0])
        assertEquals('A'.code.toByte(), v1Bytes[1])
        assertEquals('G'.code.toByte(), v1Bytes[2])
        assertEquals(5.toByte(), v1Bytes[126]) // Track number
    }

    @Test
    fun testExportMp3PreservesFullBitstream() {
        val tempDir = File.createTempFile("test_run", "dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val sourceMp3 = File(tempDir, "sample.mp3")
            // Create dummy MP3 consisting of 5 valid frames (each 417 bytes) = 2085 bytes
            val frameHeader = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
            FileOutputStream(sourceMp3).use { fos ->
                repeat(5) {
                    fos.write(frameHeader)
                    fos.write(ByteArray(413)) // Remaining frame bytes
                }
            }

            val meta = Mp3Metadata(title = "Preserved Title", artist = "Preserved Artist")
            val outputStream = ByteArrayOutputStream()
            Mp3Id3Engine.exportMp3(sourceMp3, meta, outputStream)

            val exportedBytes = outputStream.toByteArray()
            // Should contain ID3v2 tag + 2085 bytes audio + 128 bytes ID3v1 tag
            assertTrue(exportedBytes.size > 2085 + 128)

            val offsets = Mp3Id3Engine.findAudioStreamOffsets(sourceMp3)
            assertEquals(0L, offsets.audioStartOffset)
            assertEquals(sourceMp3.length(), offsets.audioEndOffset)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
