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

    @Test
    fun testFindOffsetsStripsPaddingBeforeFirstMpegFrame() {
        val tempFile = tempFolder.newFile("test_padded.mp3")
        val outStream = FileOutputStream(tempFile)

        // 1. Write an ID3v2 tag of size 20 bytes
        val dummyId3 = ByteArray(30)
        dummyId3[0] = 'I'.code.toByte()
        dummyId3[1] = 'D'.code.toByte()
        dummyId3[2] = '3'.code.toByte()
        dummyId3[3] = 3
        dummyId3[4] = 0
        dummyId3[5] = 0
        dummyId3[6] = 0
        dummyId3[7] = 0
        dummyId3[8] = 0
        dummyId3[9] = 20 // declared payload size = 20
        outStream.write(dummyId3) // 30 bytes total (10 header + 20 payload)

        // 2. Write 50 bytes of 0x00 padding
        val padding = ByteArray(50)
        outStream.write(padding)

        // 3. Write 2 valid MPEG audio frames (MPEG-1 Layer 3, 128kbps, 44.1kHz, 417 bytes each)
        val frameSize = 417
        val frame1 = ByteArray(frameSize)
        frame1[0] = 0xFF.toByte()
        frame1[1] = 0xFB.toByte()
        frame1[2] = 0x90.toByte()
        frame1[3] = 0x64.toByte()

        val frame2 = ByteArray(frameSize)
        frame2[0] = 0xFF.toByte()
        frame2[1] = 0xFB.toByte()
        frame2[2] = 0x90.toByte()
        frame2[3] = 0x64.toByte()

        outStream.write(frame1)
        outStream.write(frame2)
        outStream.close()

        val offsets = Mp3Id3Engine.findAudioStreamOffsets(tempFile)
        // audioStartOffset must NOT be 30 (the old tag end); it MUST be 80 (where the real MPEG frame sync begins)!
        assertEquals("audioStartOffset must accurately skip 50 bytes of padding to the true MPEG sync", 80L, offsets.audioStartOffset)
    }
}
