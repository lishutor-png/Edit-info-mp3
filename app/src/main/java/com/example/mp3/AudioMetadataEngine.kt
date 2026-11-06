package com.example.mp3

import android.content.Context
import java.io.File
import java.io.OutputStream

/**
 * Universal Audio Metadata Dispatcher.
 *
 * Inspects container signatures and routes requests to the proper engine:
 * - MP4 / M4A (AAC audio): Handled by Mp4MetadataEngine (native box editing, covr atom, no re-encoding)
 * - MP3 (MPEG Audio): Handled by Mp3Id3Engine (ID3v2.3, APIC frame, standard padding, frame-exact stream)
 */
object AudioMetadataEngine {

    fun extractMetadata(context: Context, file: File): Mp3Metadata {
        val format = AudioFormat.detect(file)
        val meta = when (format) {
            AudioFormat.MP4_AAC -> Mp4MetadataEngine.extractMetadata(context, file)
            else -> Mp3Id3Engine.extractMetadata(context, file)
        }
        return meta.copy(audioFormat = format)
    }

    fun exportAudio(sourceFile: File, metadata: Mp3Metadata, outputStream: OutputStream) {
        val format = AudioFormat.detect(sourceFile)
        when (format) {
            AudioFormat.MP4_AAC -> {
                Mp4MetadataEngine.exportMp4(sourceFile, metadata, outputStream)
            }
            else -> {
                Mp3Id3Engine.exportMp3(sourceFile, metadata, outputStream)
            }
        }
    }
}
