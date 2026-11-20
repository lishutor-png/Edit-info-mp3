package com.example

import com.example.mp3.AudioFormat
import com.example.mp3.AudioMetadataEngine
import com.example.mp3.Mp3Metadata
import com.example.mp3.Mp4MetadataEngine
import com.example.mp3.WavMetadataEngine
import com.mpatric.mp3agic.Mp3File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * End-to-end verification using real FFmpeg & FFprobe binaries when installed in the environment.
 * Guarded with Assume.assumeTrue so CI runners without ffmpeg skip gracefully.
 */
class FfmpegVerificationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var ffmpegAvailable = false

    @Before
    fun checkFfmpeg() {
        ffmpegAvailable = try {
            val p1 = ProcessBuilder("ffmpeg", "-version").start()
            val p2 = ProcessBuilder("ffprobe", "-version").start()
            p1.waitFor() == 0 && p2.waitFor() == 0
        } catch (e: Exception) {
            false
        }
        Assume.assumeTrue("ffmpeg/ffprobe not installed; skipping real binary verification", ffmpegAvailable)
    }

    private fun runCmd(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(*args)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return code to (stdout + if (stderr.isNotBlank()) "\nSTDERR:$stderr" else "")
    }

    private fun createRealJpegCover(): ByteArray {
        val out = tempFolder.newFile("real_cover.jpg")
        val (code, log) = runCmd(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "color=c=royalblue:s=64x64",
            "-frames:v", "1", out.absolutePath
        )
        assertEquals("Failed generating sample JPEG via ffmpeg: $log", 0, code)
        return out.readBytes()
    }

    private fun createRealPngCover(): ByteArray {
        val out = tempFolder.newFile("real_cover.png")
        val (code, log) = runCmd(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "color=c=crimson:s=64x64",
            "-frames:v", "1", out.absolutePath
        )
        assertEquals("Failed generating sample PNG via ffmpeg: $log", 0, code)
        return out.readBytes()
    }

    private fun assertFfmpegDecodesCleanly(file: File) {
        val proc = ProcessBuilder(
            "ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "null", "-"
        ).start()
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        assertEquals("FFmpeg decode exited with non-zero code for ${file.name}: $stderr", 0, code)
        assertEquals("FFmpeg reported decoding errors for ${file.name}: $stderr", "", stderr)
    }

    private fun probeDouble(file: File, entry: String): Double {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-show_entries", entry,
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        ).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(0, proc.waitFor())
        return out.lines().first().toDouble()
    }

    private fun probeAudioString(file: File, entry: String): String {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", entry,
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        ).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(0, proc.waitFor())
        return out
    }

    private fun probeAllStreamsString(file: File, entry: String): String {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-show_entries", entry,
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        ).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(0, proc.waitFor())
        return out
    }

    private fun probeFormatTags(file: File): String {
        val proc = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-show_entries", "format_tags",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath
        ).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals(0, proc.waitFor())
        return out
    }

    @Test
    fun verifyWavDisguisedAsMp3_DecodesZeroErrors_PreservesPcmAndDuration_WithCover() {
        // 1. Generate real WAV PCM 16-bit stereo 44,100 Hz (2.0s) saved with deceptive ".mp3" extension
        val sourceFile = tempFolder.newFile("Gila Lagi - Lish.mp3")
        val (genCode, genLog) = runCmd(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2",
            "-ac", "2", "-c:a", "pcm_s16le", "-f", "wav",
            sourceFile.absolutePath
        )
        assertEquals("Failed generating WAV via ffmpeg: $genLog", 0, genCode)
        val sourceDuration = probeDouble(sourceFile, "format=duration")
        assertEquals(2.0, sourceDuration, 0.05)

        // 2. Verify signature detection ignores ".mp3" extension and detects WAV
        val detected = AudioFormat.detect(sourceFile)
        assertEquals(AudioFormat.WAV, detected)
        assertEquals("wav", detected.extension)

        val srcMeta = WavMetadataEngine.extractMetadata(null, sourceFile)
        val jpegCover = createRealJpegCover()
        val pngCover = createRealPngCover()

        // 3. Export with JPEG cover art and metadata
        val exportedWavJpeg = tempFolder.newFile("Gila Lagi - Lish.wav")
        val metaJpeg = srcMeta.copy(
            title = "Gila Lagi",
            artist = "Lish",
            album = "Studio Session",
            genre = "Pop",
            year = "2026",
            trackNumber = "1",
            coverImageBytes = jpegCover,
            coverMimeType = "image/jpeg"
        )
        FileOutputStream(exportedWavJpeg).use { AudioMetadataEngine.exportAudio(sourceFile, metaJpeg, it) }

        // 4. Verify with real FFmpeg/FFprobe
        assertFfmpegDecodesCleanly(exportedWavJpeg)
        assertEquals("wav", probeAudioString(exportedWavJpeg, "format=format_name"))
        assertEquals("pcm_s16le", probeAudioString(exportedWavJpeg, "stream=codec_name"))
        assertEquals("44100", probeAudioString(exportedWavJpeg, "stream=sample_rate"))
        assertEquals("2", probeAudioString(exportedWavJpeg, "stream=channels"))
        assertTrue(
            "FFprobe should detect MJPEG cover art stream from WAV id3 chunk",
            probeAllStreamsString(exportedWavJpeg, "stream=codec_name").lines().contains("mjpeg")
        )

        val exportedDuration = probeDouble(exportedWavJpeg, "format=duration")
        assertTrue(
            "WAV duration changed! src=$sourceDuration exported=$exportedDuration",
            abs(sourceDuration - exportedDuration) < 0.01
        )
        val tagsText = probeFormatTags(exportedWavJpeg)
        assertTrue("FFprobe should read WAV title ($tagsText)", tagsText.contains("title=Gila Lagi"))
        assertTrue("FFprobe should read WAV artist ($tagsText)", tagsText.contains("artist=Lish"))
        assertTrue("FFprobe should read WAV album ($tagsText)", tagsText.contains("album=Studio Session"))

        // 5. Replace with PNG cover and verify round-trip + decode again
        val exportedWavPng = tempFolder.newFile("Gila Lagi - Lish_png.wav")
        val metaPng = metaJpeg.copy(coverImageBytes = pngCover, coverMimeType = "image/png")
        FileOutputStream(exportedWavPng).use { AudioMetadataEngine.exportAudio(exportedWavJpeg, metaPng, it) }

        assertFfmpegDecodesCleanly(exportedWavPng)
        val readBack = WavMetadataEngine.extractMetadata(null, exportedWavPng)
        assertEquals("Gila Lagi", readBack.title)
        assertEquals("Lish", readBack.artist)
        assertArrayEquals(pngCover, readBack.coverImageBytes)
        assertEquals("image/png", readBack.coverMimeType)
    }

    @Test
    fun verifyMp4AacDisguisedAsMp3_DecodesZeroErrors_PreservesAacAndDuration_WithCover() {
        // 1. Generate real AAC-LC in MP4 container (ftypmp42 / m4a) saved as deceptive ".mp3"
        val sourceFile = tempFolder.newFile("Recording_AAC.mp3")
        val (genCode, genLog) = runCmd(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "sine=frequency=523:sample_rate=44100:duration=2",
            "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "mp4",
            sourceFile.absolutePath
        )
        assertEquals("Failed generating AAC/MP4 via ffmpeg: $genLog", 0, genCode)
        val sourceDuration = probeDouble(sourceFile, "format=duration")

        // 2. Signature detection MUST choose MP4_AAC despite ".mp3" extension
        val detected = AudioFormat.detect(sourceFile)
        assertEquals(AudioFormat.MP4_AAC, detected)
        assertEquals("m4a", detected.extension)

        val srcMeta = Mp4MetadataEngine.extractMetadata(null, sourceFile)
        val jpegCover = createRealJpegCover()
        val exportedM4a = tempFolder.newFile("Recording_AAC_edited.m4a")
        val newMeta = srcMeta.copy(
            title = "Gila Lagi AAC",
            artist = "Lish",
            album = "Live Mic",
            genre = "Acoustic",
            year = "2026",
            trackNumber = "2",
            coverImageBytes = jpegCover,
            coverMimeType = "image/jpeg"
        )
        FileOutputStream(exportedM4a).use { AudioMetadataEngine.exportAudio(sourceFile, newMeta, it) }

        // 3. FFmpeg decode check: 0 errors, exact AAC codec, exact duration, cover attached_pic present
        assertFfmpegDecodesCleanly(exportedM4a)
        assertEquals("aac", probeAudioString(exportedM4a, "stream=codec_name"))
        val codecs = probeAllStreamsString(exportedM4a, "stream=codec_name").lines()
        assertTrue("MJPEG cover stream (covr) must be detected by ffprobe", codecs.contains("mjpeg"))

        val exportedDuration = probeDouble(exportedM4a, "format=duration")
        assertTrue(
            "AAC/MP4 duration changed! src=$sourceDuration exported=$exportedDuration",
            abs(sourceDuration - exportedDuration) < 0.05
        )
        val tagsText = probeFormatTags(exportedM4a)
        assertTrue("FFprobe should read M4A title ($tagsText)", tagsText.contains("title=Gila Lagi AAC"))
        assertTrue("FFprobe should read M4A artist ($tagsText)", tagsText.contains("artist=Lish"))
        assertTrue("FFprobe should read M4A album ($tagsText)", tagsText.contains("album=Live Mic"))
    }

    @Test
    fun verifyRealMp3_WithoutCover_AddAndReplaceCover_DecodesZeroErrors() {
        // 1. Generate real MP3 without any cover art
        val sourceMp3 = tempFolder.newFile("real_no_cover.mp3")
        val (genCode, genLog) = runCmd(
            "ffmpeg", "-y", "-v", "error",
            "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100:duration=2",
            "-ac", "2", "-c:a", "libmp3lame", "-b:a", "192k",
            sourceMp3.absolutePath
        )
        assertEquals("Failed generating MP3 via ffmpeg: $genLog", 0, genCode)
        val sourceDuration = probeDouble(sourceMp3, "format=duration")

        assertEquals(AudioFormat.MP3, AudioFormat.detect(sourceMp3))

        val jpegCover = createRealJpegCover()
        val exportedMp3 = tempFolder.newFile("real_with_cover.mp3")
        val meta = Mp3Metadata(
            title = "Lagu MP3 Asli",
            artist = "Lish",
            album = "Single",
            genre = "Rock",
            year = "2026",
            trackNumber = "3",
            coverImageBytes = jpegCover,
            coverMimeType = "image/jpeg"
        )
        FileOutputStream(exportedMp3).use { AudioMetadataEngine.exportAudio(sourceMp3, meta, it) }

        assertFfmpegDecodesCleanly(exportedMp3)
        assertEquals("mp3", probeAudioString(exportedMp3, "stream=codec_name"))
        val codecs = probeAllStreamsString(exportedMp3, "stream=codec_name").lines()
        assertTrue("APIC cover image stream must be detected by ffprobe", codecs.contains("mjpeg"))
        val mp3Tags = probeFormatTags(exportedMp3)
        assertTrue("FFprobe should read MP3 title ($mp3Tags)", mp3Tags.contains("title=Lagu MP3 Asli"))

        val exportedDuration = probeDouble(exportedMp3, "format=duration")
        assertTrue(
            "MP3 duration changed! src=$sourceDuration exported=$exportedDuration",
            abs(sourceDuration - exportedDuration) < 0.05
        )

        val mp3Parsed = Mp3File(exportedMp3.absolutePath)
        assertTrue(mp3Parsed.hasId3v2Tag())
        assertEquals("Lagu MP3 Asli", mp3Parsed.id3v2Tag.title)
        assertNotNull(mp3Parsed.id3v2Tag.albumImage)
        assertArrayEquals(jpegCover, mp3Parsed.id3v2Tag.albumImage)
    }
}
