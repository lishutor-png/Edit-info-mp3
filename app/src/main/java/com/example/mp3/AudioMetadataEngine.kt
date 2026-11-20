package com.example.mp3

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * Universal Audio Metadata Dispatcher.
 *
 * Inspects container signatures and routes requests to the proper engine:
 * - WAV (RIFF / WAVE PCM): Handled by WavMetadataEngine (LIST INFO + 'id3 ' chunk, PCM bit-exact)
 * - MP4 / M4A (AAC audio): Handled by Mp4MetadataEngine (native box editing, covr atom, no re-encoding)
 * - MP3 (MPEG Audio): Handled by Mp3Id3Engine (ID3v2.3, APIC frame, standard padding, frame-exact stream)
 */
object AudioMetadataEngine {

    fun extractMetadata(context: Context, file: File): Mp3Metadata {
        val format = AudioFormat.detect(file)
        val meta = when (format) {
            AudioFormat.WAV -> WavMetadataEngine.extractMetadata(context, file)
            AudioFormat.MP4_AAC -> Mp4MetadataEngine.extractMetadata(context, file)
            AudioFormat.MP3 -> Mp3Id3Engine.extractMetadata(context, file)
            AudioFormat.UNKNOWN -> throw IllegalArgumentException(
                "Format file audio tidak dikenali. Pastikan file berformat MP3, WAV, atau AAC/MP4."
            )
        }
        return meta.copy(audioFormat = format)
    }

    fun exportAudio(sourceFile: File, metadata: Mp3Metadata, outputStream: OutputStream) {
        val format = AudioFormat.detect(sourceFile)
        when (format) {
            AudioFormat.WAV -> WavMetadataEngine.exportWav(sourceFile, metadata, outputStream)
            AudioFormat.MP4_AAC -> Mp4MetadataEngine.exportMp4(sourceFile, metadata, outputStream)
            AudioFormat.MP3 -> Mp3Id3Engine.exportMp3(sourceFile, metadata, outputStream)
            AudioFormat.UNKNOWN -> throw IllegalArgumentException(
                "Format file audio tidak dikenali atau tidak didukung untuk diekspor."
            )
        }
    }
}
