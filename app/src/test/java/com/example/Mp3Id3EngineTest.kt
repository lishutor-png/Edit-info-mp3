package com.example

import com.example.mp3.Mp3Id3Engine
import com.example.mp3.Mp3Metadata
import com.mpatric.mp3agic.Mp3File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class Mp3Id3EngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Creates valid MPEG-1 Layer 3 audio frames (128 kbps, 44.1 kHz, 417 bytes each)
    private fun createMpegAudioFrames(frameCount: Int): ByteArray {
        val frameSize = 417
        val audioBytes = ByteArray(frameSize * frameCount)
        for (i in 0 until frameCount) {
            val offset = i * frameSize
            audioBytes[offset] = 0xFF.toByte()     // Sync word byte 1
            audioBytes[offset + 1] = 0xFB.toByte() // Sync word byte 2 (MPEG-1 Layer 3, no CRC)
            audioBytes[offset + 2] = 0x90.toByte() // 128 kbps, 44.1 kHz, no padding
            audioBytes[offset + 3] = 0x64.toByte() // Joint stereo
            // Distinct payload pattern to verify bit-level preservation
            for (p in 4 until frameSize) {
                audioBytes[offset + p] = ((i * 17 + p) % 256).toByte()
            }
        }
        return audioBytes
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

    /**
     * Test 1: Ekspor MP3 tanpa mengubah cover art
     */
    @Test
    fun test1_ExportWithoutChangingCoverArt() {
        val audioBytes = createMpegAudioFrames(10)
        val initialCover = sampleJpegBytes()
        val initialMeta = Mp3Metadata(
            title = "Lagu Original",
            artist = "Artis Original",
            album = "Album Original",
            genre = "Rock",
            year = "2023",
            trackNumber = "1",
            coverImageBytes = initialCover,
            coverMimeType = "image/jpeg"
        )

        // Create initial MP3 with cover art
        val rawSource = tempFolder.newFile("raw_source_1.mp3").apply { writeBytes(audioBytes) }
        val sourceFile = tempFolder.newFile("source_with_cover.mp3")
        FileOutputStream(sourceFile).use { out ->
            Mp3Id3Engine.exportMp3(rawSource, initialMeta, out)
        }

        // Export again keeping the same cover art
        val exportedFile = tempFolder.newFile("exported_same_cover.mp3")
        FileOutputStream(exportedFile).use { out ->
            Mp3Id3Engine.exportMp3(sourceFile, initialMeta, out)
        }

        // Verify with Mp3File parser
        val mp3 = Mp3File(exportedFile.absolutePath)
        assertTrue("Exported file must have ID3v2 tag", mp3.hasId3v2Tag())
        val tag = mp3.id3v2Tag
        assertEquals("Lagu Original", tag.title)
        assertEquals("Artis Original", tag.artist)
        assertEquals("Album Original", tag.album)
        assertNotNull("Album cover art must be preserved", tag.albumImage)
        assertArrayEquals("Cover art bytes must be identical", initialCover, tag.albumImage)
        assertEquals("image/jpeg", tag.albumImageMimeType)

        // Verify audio stream equivalence
        val offsets = Mp3Id3Engine.findAudioStreamOffsets(exportedFile)
        val extractedAudio = ByteArray((offsets.audioEndOffset - offsets.audioStartOffset).toInt())
        RandomAccessFile(exportedFile, "r").use { raf ->
            raf.seek(offsets.audioStartOffset)
            raf.readFully(extractedAudio)
        }
        assertArrayEquals("Audio frames must remain bit-identical", audioBytes, extractedAudio)
    }

    /**
     * Test 2: Menambahkan cover art pada MP3 yang belum memiliki gambar (Coverless MP3)
     */
    @Test
    fun test2_AddCoverToCoverlessMp3() {
        val audioBytes = createMpegAudioFrames(15)
        val coverlessFile = tempFolder.newFile("raw_no_cover.mp3")
        coverlessFile.writeBytes(audioBytes)

        val newCover = sampleJpegBytes()
        val newMeta = Mp3Metadata(
            title = "Baru Ditambah Cover",
            artist = "Penyanyi Baru",
            album = "Single 2024",
            genre = "Pop",
            year = "2024",
            trackNumber = "7",
            coverImageBytes = newCover,
            coverMimeType = "image/jpeg"
        )

        val exportedFile = tempFolder.newFile("exported_added_cover.mp3")
        FileOutputStream(exportedFile).use { out ->
            Mp3Id3Engine.exportMp3(coverlessFile, newMeta, out)
        }

        val mp3 = Mp3File(exportedFile.absolutePath)
        assertTrue("Must have ID3v2 tag", mp3.hasId3v2Tag())
        val tag = mp3.id3v2Tag
        assertEquals("Baru Ditambah Cover", tag.title)
        assertEquals("Penyanyi Baru", tag.artist)
        assertNotNull("Newly added cover art must be present", tag.albumImage)
        assertArrayEquals("Added cover art bytes must match exactly", newCover, tag.albumImage)
        assertEquals("image/jpeg", tag.albumImageMimeType)

        // Verify ID3v1 trailer is present
        assertTrue("Must have ID3v1 trailer", mp3.hasId3v1Tag())
        assertEquals("Baru Ditambah Cover", mp3.id3v1Tag.title)

        // Verify audio frames are bit-identical
        val offsets = Mp3Id3Engine.findAudioStreamOffsets(exportedFile)
        val extractedAudio = ByteArray((offsets.audioEndOffset - offsets.audioStartOffset).toInt())
        RandomAccessFile(exportedFile, "r").use { raf ->
            raf.seek(offsets.audioStartOffset)
            raf.readFully(extractedAudio)
        }
        assertArrayEquals("Audio payload must be untouched", audioBytes, extractedAudio)
    }

    /**
     * Test 3: Mengganti cover art pada MP3 yang sudah memiliki gambar (No duplicate APIC frames)
     */
    @Test
    fun test3_ReplaceCoverOnMp3WithExistingCover() {
        val audioBytes = createMpegAudioFrames(8)
        val coverA = sampleJpegBytes()
        val coverB = samplePngBytes()

        val metaOld = Mp3Metadata(
            title = "Track A",
            artist = "Artist A",
            album = "Album A",
            coverImageBytes = coverA,
            coverMimeType = "image/jpeg"
        )

        val rawSource = tempFolder.newFile("raw_source_3.mp3").apply { writeBytes(audioBytes) }
        val initialFile = tempFolder.newFile("initial_cover_a.mp3")
        FileOutputStream(initialFile).use { out ->
            Mp3Id3Engine.exportMp3(rawSource, metaOld, out)
        }

        // Replace with Cover B (PNG)
        val metaNew = metaOld.copy(
            title = "Track A (Replaced)",
            coverImageBytes = coverB,
            coverMimeType = "image/png"
        )

        val replacedFile = tempFolder.newFile("replaced_cover_b.mp3")
        FileOutputStream(replacedFile).use { out ->
            Mp3Id3Engine.exportMp3(initialFile, metaNew, out)
        }

        val mp3 = Mp3File(replacedFile.absolutePath)
        assertTrue(mp3.hasId3v2Tag())
        val tag = mp3.id3v2Tag
        assertEquals("Track A (Replaced)", tag.title)
        assertNotNull("New cover must be present", tag.albumImage)
        assertArrayEquals("Must have Cover B PNG bytes", coverB, tag.albumImage)
        assertEquals("image/png", tag.albumImageMimeType)

        // Verify audio is still bit-identical
        val offsets = Mp3Id3Engine.findAudioStreamOffsets(replacedFile)
        val extractedAudio = ByteArray((offsets.audioEndOffset - offsets.audioStartOffset).toInt())
        RandomAccessFile(replacedFile, "r").use { raf ->
            raf.seek(offsets.audioStartOffset)
            raf.readFully(extractedAudio)
        }
        assertArrayEquals(audioBytes, extractedAudio)
    }

    /**
     * Test 4: Mengedit metadata tanpa mengubah audio
     */
    @Test
    fun test4_EditMetadataWithoutChangingAudio() {
        val audioBytes = createMpegAudioFrames(12)
        val rawFile = tempFolder.newFile("edit_meta_test.mp3")
        rawFile.writeBytes(audioBytes)

        val meta = Mp3Metadata(
            title = "Harmoni Alam",
            artist = "Komposer Indonesia",
            album = "Suara Nusantara",
            genre = "Acoustic",
            year = "2025",
            trackNumber = "3"
        )

        val exportedFile = tempFolder.newFile("meta_updated.mp3")
        FileOutputStream(exportedFile).use { out ->
            Mp3Id3Engine.exportMp3(rawFile, meta, out)
        }

        val mp3 = Mp3File(exportedFile.absolutePath)
        assertTrue(mp3.hasId3v2Tag())
        val tag = mp3.id3v2Tag
        assertEquals("Harmoni Alam", tag.title)
        assertEquals("Komposer Indonesia", tag.artist)
        assertEquals("Suara Nusantara", tag.album)
        assertEquals("Acoustic", tag.genreDescription)
        assertEquals("2025", tag.year)
        assertEquals("3", tag.track)

        val offsets = Mp3Id3Engine.findAudioStreamOffsets(exportedFile)
        assertEquals("Audio stream size must match original exactly", audioBytes.size.toLong(), offsets.audioEndOffset - offsets.audioStartOffset)
    }

    /**
     * Test 5: Menggunakan gambar JPEG dan PNG
     */
    @Test
    fun test5_CoverArtJpegAndPngSupport() {
        val jpegMeta = Mp3Metadata(
            title = "JPEG Song",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )
        val jpegTagBytes = Mp3Id3Engine.buildId3v23Tag(jpegMeta)
        assertTrue(jpegTagBytes.isNotEmpty())

        val pngMeta = Mp3Metadata(
            title = "PNG Song",
            coverImageBytes = samplePngBytes(),
            coverMimeType = "image/png"
        )
        val pngTagBytes = Mp3Id3Engine.buildId3v23Tag(pngMeta)
        assertTrue(pngTagBytes.isNotEmpty())

        // Verify MIME type string presence in tag
        val jpegTagStr = String(jpegTagBytes, Charsets.ISO_8859_1)
        assertTrue("JPEG tag should contain image/jpeg", jpegTagStr.contains("image/jpeg"))

        val pngTagStr = String(pngTagBytes, Charsets.ISO_8859_1)
        assertTrue("PNG tag should contain image/png", pngTagStr.contains("image/png"))
    }

    /**
     * Test 6: Verifikasi standard padding dan synchsafe header ID3v2.3
     */
    @Test
    fun test6_ExportedMp3HasStandardPaddingAndSynchsafeHeaders() {
        val meta = Mp3Metadata(
            title = "Test Padding Song",
            artist = "Padding Artist",
            album = "Padding Album",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )

        val tagBytes = Mp3Id3Engine.buildId3v23Tag(meta)
        // 1. Check ID3v2.3 header
        assertEquals('I'.code.toByte(), tagBytes[0])
        assertEquals('D'.code.toByte(), tagBytes[1])
        assertEquals('3'.code.toByte(), tagBytes[2])
        assertEquals(3.toByte(), tagBytes[3]) // v2.3
        assertEquals(0.toByte(), tagBytes[4]) // revision 0

        // 2. Check synchsafe size calculation
        val s0 = tagBytes[6].toInt() and 0x7F
        val s1 = tagBytes[7].toInt() and 0x7F
        val s2 = tagBytes[8].toInt() and 0x7F
        val s3 = tagBytes[9].toInt() and 0x7F
        val declaredPayloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3

        assertEquals(tagBytes.size - 10, declaredPayloadSize)

        // 3. Verify that the end of ID3v2.3 tag contains zero padding buffer
        val lastBytes = tagBytes.takeLast(64)
        assertTrue("Tag end must contain standard zero padding block", lastBytes.all { it == 0.toByte() })
    }

    /**
     * Test 7: Memastikan audio hasil ekspor identik dengan audio sumber pada level frame MPEG
     */
    @Test
    fun test7_AudioStreamBitIdenticalAtMpegFrameLevel() {
        val frameCount = 30
        val originalAudio = createMpegAudioFrames(frameCount)

        val source = tempFolder.newFile("frame_test_source.mp3")
        source.writeBytes(originalAudio)

        val meta = Mp3Metadata(
            title = "Bit Exact Verification",
            artist = "Verification Lab",
            album = "Sound Quality 100%",
            genre = "Classical",
            year = "2024",
            trackNumber = "12",
            coverImageBytes = sampleJpegBytes(),
            coverMimeType = "image/jpeg"
        )

        val exported = tempFolder.newFile("frame_test_exported.mp3")
        FileOutputStream(exported).use { out ->
            Mp3Id3Engine.exportMp3(source, meta, out)
        }

        val offsets = Mp3Id3Engine.findAudioStreamOffsets(exported)
        assertTrue("Audio start offset must be after ID3 tag", offsets.audioStartOffset > 0)
        assertTrue("Has ID3v1 trailer", offsets.hasId3v1)
        assertEquals("Extracted audio byte count must match frameCount * 417 exactly", (frameCount * 417).toLong(), offsets.audioEndOffset - offsets.audioStartOffset)

        // Read audio stream directly from exported file
        val exportedAudio = ByteArray((frameCount * 417))
        RandomAccessFile(exported, "r").use { raf ->
            raf.seek(offsets.audioStartOffset)
            raf.readFully(exportedAudio)
        }

        // Verify each individual frame sync word and payload
        for (i in 0 until frameCount) {
            val offset = i * 417
            assertEquals("Frame $i byte 0 must be 0xFF", 0xFF.toByte(), exportedAudio[offset])
            assertEquals("Frame $i byte 1 must be 0xFB", 0xFB.toByte(), exportedAudio[offset + 1])
            assertEquals("Frame $i byte 2 must be 0x90", 0x90.toByte(), exportedAudio[offset + 2])
            assertEquals("Frame $i byte 3 must be 0x64", 0x64.toByte(), exportedAudio[offset + 3])
        }

        assertArrayEquals("Entire audio bitstream must be bit-for-bit identical to source", originalAudio, exportedAudio)
    }

    /**
     * Test 8: findAudioStreamOffsets accurately skips any padding before the first MPEG frame
     */
    @Test
    fun test8_FindOffsetsStripsPaddingBeforeFirstMpegFrame() {
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
        outStream.write(dummyId3)

        // 2. Write 50 bytes of 0x00 padding
        val padding = ByteArray(50)
        outStream.write(padding)

        // 3. Write 2 valid MPEG audio frames (417 bytes each)
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
        // audioStartOffset must accurately skip 50 bytes of padding to the true MPEG sync at 80
        assertEquals("audioStartOffset must accurately skip 50 bytes of padding to the true MPEG sync", 80L, offsets.audioStartOffset)
    }

    /**
     * Test 9: ID3v1 trailer formatting and genre mapping
     */
    @Test
    fun test9_Id3v1TrailerProperlyFormatted() {
        val meta = Mp3Metadata(
            title = "Lagu Keren",
            artist = "Artis Hebat",
            album = "Album Baru",
            genre = "Rock",
            year = "2024",
            trackNumber = "5"
        )

        val v1Bytes = Mp3Id3Engine.buildId3v1Tag(meta)
        assertEquals(128, v1Bytes.size)

        // Check "TAG"
        assertEquals('T'.code.toByte(), v1Bytes[0])
        assertEquals('A'.code.toByte(), v1Bytes[1])
        assertEquals('G'.code.toByte(), v1Bytes[2])

        // Check track number at byte 126
        assertEquals(5.toByte(), v1Bytes[126])

        // Check Rock genre code (17) at byte 127
        assertEquals(17.toByte(), v1Bytes[127])
    }
}
