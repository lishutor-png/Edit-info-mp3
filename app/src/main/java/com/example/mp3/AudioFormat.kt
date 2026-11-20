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
    WAV("wav", "audio/x-wav", "WAV Audio (PCM)"),
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
                    val fileLength = file.length()
                    val header = ByteArray(16)
                    val read = raf.read(header)

                    // 1. Check RIFF / WAVE container signature ("RIFF" at 0..3 and "WAVE" at 8..11)
                    if (read >= 12 && isRiffWaveAt(header, 0)) {
                        return@use WAV
                    }

                    // 2. Check MP4 / M4A container signature ("ftyp" box at offset 4)
                    // Examples: ftypM4A , ftypmp41, ftypmp42, ftypisom, ftypdash
                    if (read >= 8 && isFtypAt(header, 4)) {
                        return@use MP4_AAC
                    }

                    // 3. Check ID3v2 tag ("ID3" at offset 0..2)
                    // Inspect what follows the ID3v2 tag in case a non-MP3 container (like WAV or MP4)
                    // was mistakenly wrapped with an ID3v2 header.
                    if (read >= 10 &&
                        header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte()
                    ) {
                        val flags = header[5].toInt() and 0xFF
                        val s0 = header[6].toInt() and 0x7F
                        val s1 = header[7].toInt() and 0x7F
                        val s2 = header[8].toInt() and 0x7F
                        val s3 = header[9].toInt() and 0x7F
                        val tagPayloadSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                        var id3TotalSize = 10L + tagPayloadSize
                        if ((flags and 0x10) != 0) id3TotalSize += 10L

                        if (id3TotalSize in 10L until (fileLength - 12L)) {
                            raf.seek(id3TotalSize)
                            val afterId3 = ByteArray(16)
                            val afterRead = raf.read(afterId3)
                            if (afterRead >= 12 && isRiffWaveAt(afterId3, 0)) {
                                return@use WAV
                            }
                            if (afterRead >= 8 && isFtypAt(afterId3, 4)) {
                                return@use MP4_AAC
                            }
                        }
                        return@use MP3
                    }

                    // 4. Check raw MPEG audio frame sync at offset 0
                    if (read >= 4 && isValidMpegFrameHeader(header, 0)) {
                        return@use MP3
                    }

                    // 5. Scan first 4096 bytes for RIFF+WAVE, ftyp, or ID3 in case of leading padding
                    val scanWindow = minOf(fileLength, 4096L).toInt()
                    raf.seek(0)
                    val scanBuf = ByteArray(scanWindow)
                    val scanRead = raf.read(scanBuf)
                    if (scanRead >= 12) {
                        for (i in 0..(scanRead - 12)) {
                            if (isRiffWaveAt(scanBuf, i)) {
                                return@use WAV
                            }
                        }
                    }
                    if (scanRead >= 8) {
                        for (i in 4..(scanRead - 4)) {
                            if (isFtypAt(scanBuf, i)) {
                                return@use MP4_AAC
                            }
                        }
                    }
                    if (scanRead >= 10) {
                        for (i in 0..(scanRead - 10)) {
                            if (scanBuf[i] == 'I'.code.toByte() &&
                                scanBuf[i + 1] == 'D'.code.toByte() &&
                                scanBuf[i + 2] == '3'.code.toByte()
                            ) {
                                return@use MP3
                            }
                        }
                    }

                    // 6. Scan first 64 KB for valid MPEG audio frame sync
                    val mpegScanWindow = minOf(fileLength, 65536L).toInt()
                    raf.seek(0)
                    val mpegBuf = ByteArray(mpegScanWindow)
                    val mpegRead = raf.read(mpegBuf)
                    if (mpegRead >= 4) {
                        for (i in 0..(mpegRead - 4)) {
                            if (isValidMpegFrameHeader(mpegBuf, i)) {
                                return@use MP3
                            }
                        }
                    }

                    UNKNOWN
                }
            } catch (e: Exception) {
                UNKNOWN
            }
        }

        private fun isRiffWaveAt(buf: ByteArray, offset: Int): Boolean {
            if (offset + 12 > buf.size) return false
            return buf[offset] == 'R'.code.toByte() &&
                buf[offset + 1] == 'I'.code.toByte() &&
                buf[offset + 2] == 'F'.code.toByte() &&
                buf[offset + 3] == 'F'.code.toByte() &&
                buf[offset + 8] == 'W'.code.toByte() &&
                buf[offset + 9] == 'A'.code.toByte() &&
                buf[offset + 10] == 'V'.code.toByte() &&
                buf[offset + 11] == 'E'.code.toByte()
        }

        private fun isFtypAt(buf: ByteArray, offset: Int): Boolean {
            if (offset + 4 > buf.size) return false
            return buf[offset] == 'f'.code.toByte() &&
                buf[offset + 1] == 't'.code.toByte() &&
                buf[offset + 2] == 'y'.code.toByte() &&
                buf[offset + 3] == 'p'.code.toByte()
        }

        private fun isValidMpegFrameHeader(buf: ByteArray, offset: Int): Boolean {
            if (offset + 4 > buf.size) return false
            val b0 = buf[offset].toInt() and 0xFF
            val b1 = buf[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val version = (b1 ushr 3) and 0x03
                val layer = (b1 ushr 1) and 0x03
                val b2 = buf[offset + 2].toInt() and 0xFF
                val bitrateIndex = (b2 ushr 4) and 0x0F
                val sampleRateIndex = (b2 ushr 2) and 0x03
                return version != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 15 && sampleRateIndex != 3
            }
            return false
        }
    }
}
