package com.example

import com.example.mp3.Mp3Id3Engine
import com.example.mp3.Mp3Metadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class Mp3Id3EngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBuildId3v23HeaderAndFrames() {
        val meta = Mp3Metadata(
            title = "Lagu Keren",
            artist = "Artis Hebat",
            album = "Album Baru",
            year = "2024",
            genre = "Acoustic",
            trackNumber = "2"
        )

        val id3Bytes = Mp3Id3Engine.buildId3v23Tag(meta)
        assertTrue("ID3 tag must be at least 10 bytes", id3Bytes.size >= 10)

        // Check header "ID3"
        assertEquals('I'.code.toByte(), id3Bytes[0])
        assertEquals('D'.code.toByte(), id3Bytes[1])
        assertEquals('3'.code.toByte(), id3Bytes[2])
        // Version 2.3
        assertEquals(3.toByte(), id3Bytes[3])
        assertEquals(0.toByte(), id3Bytes[4])

        // Verify syncsafe size calculation
        val s0 = id3Bytes[6].toInt() and 0x7F
        val s1 = id3Bytes[7].toInt() and 0x7F
        val s2 = id3Bytes[8].toInt() and 0x7F
        val s3 = id3Bytes[9].toInt() and 0x7F
        val payloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3

        assertEquals(id3Bytes.size - 10, payloadSize)
    }

    @Test
    fun testBuildId3v1Trailer() {
        val meta = Mp3Metadata(
            title = "Lagu Keren",
            artist = "Artis Hebat",
            album = "Album Baru",
            year = "2024",
            trackNumber = "5"
        )

        val id3v1Bytes = Mp3Id3Engine.buildId3v1Tag(meta)
        assertEquals(128, id3v1Bytes.size)

        // Check "TAG"
        assertEquals('T'.code.toByte(), id3v1Bytes[0])
        assertEquals('A'.code.toByte(), id3v1Bytes[1])
        assertEquals('G'.code.toByte(), id3v1Bytes[2])

        // Check track number at byte 126
        assertEquals(5.toByte(), id3v1Bytes[126])
    }

    @Test
    fun testExportMp3PreservesAudioStream() {
        val tempFile = tempFolder.newFile("test_source.mp3")
        val rawAudioPayload = ByteArray(1000) { (it % 256).toByte() }

        // Write an initial MP3 with raw audio
        FileOutputStream(tempFile).use { it.write(rawAudioPayload) }

        val offsetsBefore = Mp3Id3Engine.findAudioStreamOffsets(tempFile)
        assertEquals(0L, offsetsBefore.audioStartOffset)
        assertEquals(1000L, offsetsBefore.audioEndOffset)

        // Now export with metadata
        val meta = Mp3Metadata(
            title = "Judul Baru",
            artist = "Artis Baru",
            album = "Album Baru"
        )
        val exportedFile = tempFolder.newFile("test_exported.mp3")
        FileOutputStream(exportedFile).use { out ->
            Mp3Id3Engine.exportMp3(tempFile, meta, out)
        }

        // Find offsets of exported file
        val offsetsAfter = Mp3Id3Engine.findAudioStreamOffsets(exportedFile)
        assertTrue("Audio start should be > 0 due to ID3v2 header", offsetsAfter.audioStartOffset > 0)
        assertTrue("Should have ID3v1 trailer", offsetsAfter.hasId3v1)
        val audioLength = offsetsAfter.audioEndOffset - offsetsAfter.audioStartOffset
        assertEquals("Audio stream byte count must remain exactly intact", 1000L, audioLength)

        // Verify byte-for-byte audio stream equivalence
        val extractedAudio = ByteArray(1000)
        val raf = java.io.RandomAccessFile(exportedFile, "r")
        raf.seek(offsetsAfter.audioStartOffset)
        raf.readFully(extractedAudio)
        raf.close()

        assertTrue("Extracted audio bytes must be bit-identical to source audio", rawAudioPayload.contentEquals(extractedAudio))
    }
}
