package com.example.mp3

import java.io.File
import java.io.RandomAccessFile

/**
 * Audio container and codec format detected strictly by magic bytes / container signature.
 */
enum class AudioFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String
) {
    MP3("mp3", "audio/mpeg", "MP3 Audio"),
    MP4_AAC("m4a", "audio/mp4", "AAC Audio (MP4 Container)"),
    UNKNOWN("mp3", "audio/mpeg", "Audio");

    companion object {
        /**
         * Detect audio format strictly based on file signature / container magic bytes,
         * NEVER based on the file extension name.
         */
        fun detect(file: File): AudioFormat {
            if (!file.exists() || file.length() < 8) return UNKNOWN

            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(16)
                    val read = raf.read(header)
                    if (read >= 8) {
                        // Check for MP4 container ("ftyp" box at offset 4)
                        // Examples: ftypM4A , ftypmp41, ftypmp42, ftypisom, ftypdash
                        if (header[4] == 'f'.code.toByte() &&
                            header[5] == 't'.code.toByte() &&
                            header[6] == 'y'.code.toByte() &&
                            header[7] == 'p'.code.toByte()
                        ) {
                            return@use MP4_AAC
                        }
                    }

                    if (read >= 3) {
                        // Check for ID3v2 tag ("ID3")
                        if (header[0] == 'I'.code.toByte() &&
                            header[1] == 'D'.code.toByte() &&
                            header[2] == '3'.code.toByte()
                        ) {
                            return@use MP3
                        }
                    }

                    if (read >= 2) {
                        // Check for MPEG audio frame sync 11 bits (0xFF and top 3 bits of byte 1 set)
                        val b0 = header[0].toInt() and 0xFF
                        val b1 = header[1].toInt() and 0xFF
                        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                            return@use MP3
                        }
                    }

                    // Scan first 1024 bytes for 'ftyp' or 'ID3' in case of leading padding
                    val scanWindow = minOf(file.length(), 1024L).toInt()
                    raf.seek(0)
                    val scanBuf = ByteArray(scanWindow)
                    val scanRead = raf.read(scanBuf)
                    if (scanRead >= 8) {
                        for (i in 0..(scanRead - 8)) {
                            if (scanBuf[i] == 'f'.code.toByte() &&
                                scanBuf[i + 1] == 't'.code.toByte() &&
                                scanBuf[i + 2] == 'y'.code.toByte() &&
                                scanBuf[i + 3] == 'p'.code.toByte()
                            ) {
                                return@use MP4_AAC
                            }
                        }
                    }

                    // Default to MP3
                    MP3
                }
            } catch (e: Exception) {
                UNKNOWN
            }
        }
    }
}
