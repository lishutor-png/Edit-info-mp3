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
    fun testExportMp3FallbackPreservesFullBitstream() {
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
