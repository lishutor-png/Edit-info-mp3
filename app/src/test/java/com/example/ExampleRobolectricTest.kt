package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LishEdInfo", appName)
  }

  @Test
  fun `test export and parse metadata with cover`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val sampleFile = com.example.mp3.SampleMp3Helper.createSampleMp3File(context)
    val extracted = com.example.mp3.Mp3Id3Engine.extractMetadata(context, sampleFile)
    println("Sample title: ${extracted.title}, artist: ${extracted.artist}, hasCover: ${extracted.coverImageBytes != null}")

    val outFile = java.io.File(context.cacheDir, "test_out.mp3")
    val newMeta = extracted.copy(
        title = "Judul Baru Diedit",
        artist = "Artis Baru Diedit",
        album = "Album Baru Diedit"
    )
    java.io.FileOutputStream(outFile).use { fos ->
        com.example.mp3.Mp3Id3Engine.exportMp3(sampleFile, newMeta, fos)
    }

    val reExtracted = com.example.mp3.Mp3Id3Engine.extractMetadata(context, outFile)
    println("Re-extracted title: ${reExtracted.title}, artist: ${reExtracted.artist}, hasCover: ${reExtracted.coverImageBytes != null}")

    val bytes = java.io.FileInputStream(outFile).use { it.readNBytes(100) }
    println("First 100 bytes: " + bytes.joinToString(" ") { String.format("%02X", it) })
    println("As ASCII: " + bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString(""))
    
    assertEquals("Judul Baru Diedit", reExtracted.title)
    assertEquals("Artis Baru Diedit", reExtracted.artist)
    assertEquals("Album Baru Diedit", reExtracted.album)
    assertEquals(true, reExtracted.coverImageBytes != null)

    val offsets = com.example.mp3.Mp3Id3Engine.findAudioStreamOffsets(outFile)
    println("outFile length: ${outFile.length()}, audioStart: ${offsets.audioStartOffset}, audioEnd: ${offsets.audioEndOffset}")
    assertTrue("Audio start offset must be > 0", offsets.audioStartOffset > 0)
    assertTrue("Audio end offset must be > audio start", offsets.audioEndOffset > offsets.audioStartOffset)

    // Verify that the byte at audioStartOffset is a valid MPEG sync header
    val rafOut = java.io.RandomAccessFile(outFile, "r")
    val atAudioStart = ByteArray(4)
    rafOut.seek(offsets.audioStartOffset)
    rafOut.readFully(atAudioStart)
    rafOut.close()

    val b0 = atAudioStart[0].toInt() and 0xFF
    val b1 = atAudioStart[1].toInt() and 0xFF
    println("Byte 0: ${String.format("%02X", b0)}, Byte 1: ${String.format("%02X", b1)}")
    assertEquals(0xFF, b0)
    assertEquals(0xE0, b1 and 0xE0)
    val frameSize = com.example.mp3.Mp3Id3Engine.getMpegFrameSize(atAudioStart, 0, 4)
    assertTrue("Must be a valid MPEG frame", frameSize > 0)
  }
}
