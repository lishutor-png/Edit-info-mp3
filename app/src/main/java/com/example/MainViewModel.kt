package com.example

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mp3.ImageUtils
import com.example.mp3.Mp3Id3Engine
import com.example.mp3.Mp3Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class MainUiState(
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val sourceFile: File? = null,
    val metadata: Mp3Metadata? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val exportedSuccessUri: Uri? = null,
    val exportedFileName: String = "",
    val showExportSuccessDialog: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var playbackTrackerJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        stopPlaybackTracker()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun loadAudioFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Membaca file MP3...") }
            try {
                val context = getApplication<Application>()
                val tempInput = File(context.cacheDir, "imported_source.mp3")

                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempInput).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (!tempInput.exists() || tempInput.length() == 0L) {
                    throw IllegalStateException("File tidak dapat dibaca atau kosong.")
                }

                val detectedMeta = withContext(Dispatchers.IO) {
                    Mp3Id3Engine.extractMetadata(context, tempInput)
                }

                // If title is missing, try reading from ContentResolver displayName
                val finalMeta = if (detectedMeta.title.isBlank() || detectedMeta.title == "Lagu") {
                    val resolvedName = getFileNameFromUri(context, uri) ?: tempInput.name
                    val cleanTitle = resolvedName.substringBeforeLast(".")
                    detectedMeta.copy(title = cleanTitle, fileName = resolvedName)
                } else {
                    detectedMeta
                }

                prepareAudioPlayer(tempInput)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sourceFile = tempInput,
                        metadata = finalMeta,
                        statusMessage = "File berhasil dimuat."
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(isLoading = false, statusMessage = "Gagal membaca audio: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun getFileNameFromUri(context: Application, uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        } catch (ignored: Exception) {}
        return name
    }

    // --- Metadata Edit Methods ---

    fun updateTitle(title: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(title = title) ?: Mp3Metadata(title = title)
            state.copy(metadata = meta)
        }
    }

    fun updateArtist(artist: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(artist = artist) ?: Mp3Metadata(artist = artist)
            state.copy(metadata = meta)
        }
    }

    fun updateAlbum(album: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(album = album) ?: Mp3Metadata(album = album)
            state.copy(metadata = meta)
        }
    }

    fun updateGenre(genre: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(genre = genre) ?: Mp3Metadata(genre = genre)
            state.copy(metadata = meta)
        }
    }

    fun updateYear(year: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(year = year) ?: Mp3Metadata(year = year)
            state.copy(metadata = meta)
        }
    }

    fun updateTrackNumber(track: String) {
        _uiState.update { state ->
            val meta = state.metadata?.copy(trackNumber = track) ?: Mp3Metadata(trackNumber = track)
            state.copy(metadata = meta)
        }
    }

    fun autoFillFromFileName() {
        val rawName = _uiState.value.metadata?.fileName?.substringBeforeLast(".") ?: return
        val parts = rawName.split(" - ")
        if (parts.size >= 2) {
            val artist = parts[0].replace(Regex("^\\d+[.\\s_-]*"), "").trim()
            val title = parts[1].trim()
            _uiState.update { state ->
                val meta = state.metadata?.copy(title = title, artist = artist)
                state.copy(metadata = meta, statusMessage = "Judul & Artis terisi otomatis.")
            }
        } else {
            val title = rawName.replace(Regex("^\\d+[.\\s_-]*"), "").trim()
            _uiState.update { state ->
                val meta = state.metadata?.copy(title = title)
                state.copy(metadata = meta, statusMessage = "Judul terisi dari nama file.")
            }
        }
    }

    fun updateCoverImage(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = ImageUtils.compressBitmapToCoverBytes(bitmap, quality = 80, maxDimension = 600)
            _uiState.update { state ->
                val meta = state.metadata?.copy(
                    coverImageBytes = bytes,
                    coverMimeType = "image/jpeg",
                    coverBitmap = bitmap
                ) ?: Mp3Metadata(
                    coverImageBytes = bytes,
                    coverMimeType = "image/jpeg",
                    coverBitmap = bitmap
                )
                state.copy(metadata = meta, statusMessage = "Sampul berhasil diperbarui.")
            }
        }
    }

    fun removeCoverImage() {
        _uiState.update { state ->
            val meta = state.metadata?.copy(
                coverImageBytes = null,
                coverBitmap = null
            )
            state.copy(metadata = meta, statusMessage = "Sampul dihapus.")
        }
    }

    /**
     * Save directly to system Music directory via MediaStore so stock music players immediately show it.
     */
    fun saveToMusicFolder() {
        val state = _uiState.value
        val source = state.sourceFile
        val meta = state.metadata
        if (source == null || meta == null) {
            _uiState.update { it.copy(statusMessage = "Pilih file MP3 terlebih dahulu.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, statusMessage = "Menyimpan ke Pemutar Musik...") }
            try {
                val context = getApplication<Application>()
                val targetTitle = meta.title.ifBlank { "Lagu" }.trim()
                val targetArtist = meta.artist.ifBlank { "Artis" }.trim()
                val targetAlbum = meta.album.ifBlank { targetTitle }.trim()
                val safeFileName = "${targetTitle} - ${targetArtist}.mp3"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                val tempFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.mp3")
                val previewFile = File(context.cacheDir, "current_editing.mp3")

                val resultUri: Uri? = withContext(Dispatchers.IO) {
                    // Export to temporary file first
                    FileOutputStream(tempFile).use { out ->
                        Mp3Id3Engine.exportMp3(source, meta, out)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                            val selectionArgs = arrayOf(safeFileName, "%LishEdInfo%")
                            context.contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
                        } catch (ignored: Exception) {}

                        val contentValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                            put(MediaStore.Audio.Media.TITLE, targetTitle)
                            put(MediaStore.Audio.Media.ARTIST, targetArtist)
                            put(MediaStore.Audio.Media.ALBUM, targetAlbum)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                            if (meta.genre.isNotBlank()) {
                                put(MediaStore.Audio.Media.GENRE, meta.genre.trim())
                            }
                            if (meta.year.isNotBlank()) {
                                meta.year.toIntOrNull()?.let { y -> put(MediaStore.Audio.Media.YEAR, y) }
                            }
                            if (meta.trackNumber.isNotBlank()) {
                                meta.trackNumber.toIntOrNull()?.let { t -> put(MediaStore.Audio.Media.TRACK, t) }
                            }
                            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LishEdInfo/")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }

                        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                tempFile.inputStream().use { input ->
                                    input.copyTo(out)
                                }
                                out.flush()
                            }
                            contentValues.clear()
                            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            contentValues.put(MediaStore.Audio.Media.SIZE, tempFile.length())
                            if (meta.durationMs > 0) {
                                contentValues.put(MediaStore.Audio.Media.DURATION, meta.durationMs)
                            }
                            context.contentResolver.update(uri, contentValues, null, null)

                            var physicalPath: String? = null
                            try {
                                val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
                                cursor?.use { c ->
                                    if (c.moveToFirst()) physicalPath = c.getString(0)
                                }
                            } catch (ignored: Exception) {}

                            if (physicalPath.isNullOrBlank()) {
                                physicalPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "LishEdInfo/$safeFileName").absolutePath
                            }
                            MediaScannerConnection.scanFile(context, arrayOf(physicalPath), arrayOf("audio/mpeg"), null)

                            // Also write cover.jpg for OEM players that scan directory artwork
                            if (meta.coverImageBytes != null && meta.coverImageBytes.isNotEmpty()) {
                                try {
                                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                                    val outFolder = File(musicDir, "LishEdInfo")
                                    if (outFolder.exists() || outFolder.mkdirs()) {
                                        val coverFile = File(outFolder, "cover.jpg")
                                        FileOutputStream(coverFile).use { it.write(meta.coverImageBytes) }
                                        MediaScannerConnection.scanFile(context, arrayOf(coverFile.absolutePath), arrayOf("image/jpeg"), null)
                                    }
                                } catch (ignored: Exception) {}
                            }
                        }
                        uri
                    } else {
                        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val outFolder = File(musicDir, "LishEdInfo").apply { mkdirs() }
                        val outFile = File(outFolder, safeFileName)
                        tempFile.copyTo(outFile, overwrite = true)

                        if (meta.coverImageBytes != null && meta.coverImageBytes.isNotEmpty()) {
                            try {
                                val coverFile = File(outFolder, "cover.jpg")
                                FileOutputStream(coverFile).use { it.write(meta.coverImageBytes) }
                                MediaScannerConnection.scanFile(context, arrayOf(coverFile.absolutePath), arrayOf("image/jpeg"), null)
                            } catch (ignored: Exception) {}
                        }

                        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("audio/mpeg"), null)
                        Uri.fromFile(outFile)
                    }
                }

                if (resultUri != null) {
                    tempFile.copyTo(previewFile, overwrite = true)
                    tempFile.delete()
                    prepareAudioPlayer(previewFile)

                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            sourceFile = previewFile,
                            metadata = meta.copy(
                                title = targetTitle,
                                artist = targetArtist,
                                album = targetAlbum
                            ),
                            statusMessage = "Berhasil disimpan! Lagu siap diputar di pemutar musik.",
                            exportedSuccessUri = resultUri,
                            exportedFileName = safeFileName,
                            showExportSuccessDialog = true
                        )
                    }
                } else {
                    tempFile.delete()
                    throw IllegalStateException("Gagal membuat file penyimpanan.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isExporting = false, statusMessage = "Gagal menyimpan: ${e.localizedMessage}") }
            }
        }
    }

    /**
     * Export to specific destination chosen by user via SAF (CreateDocument).
     */
    fun exportToDestinationUri(destinationUri: Uri) {
        val state = _uiState.value
        val source = state.sourceFile
        val meta = state.metadata
        if (source == null || meta == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, statusMessage = "Mengekspor file MP3...") }
            try {
                val context = getApplication<Application>()
                val tempFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.mp3")
                val previewFile = File(context.cacheDir, "current_editing.mp3")

                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile).use { out ->
                        Mp3Id3Engine.exportMp3(source, meta, out)
                    }
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        out.flush()
                    }
                }

                tempFile.copyTo(previewFile, overwrite = true)
                tempFile.delete()
                prepareAudioPlayer(previewFile)

                val targetTitle = meta.title.ifBlank { "Lagu" }.trim()
                val fileName = getFileNameFromUri(context, destinationUri) ?: "${targetTitle}.mp3"

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        sourceFile = previewFile,
                        statusMessage = "Ekspor selesai! MP3 berhasil disimpan.",
                        exportedSuccessUri = destinationUri,
                        exportedFileName = fileName,
                        showExportSuccessDialog = true
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isExporting = false, statusMessage = "Gagal mengekspor: ${e.localizedMessage}") }
            }
        }
    }

    fun dismissSuccessDialog() {
        _uiState.update { it.copy(showExportSuccessDialog = false) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    // --- Audio Player Controls ---

    private fun prepareAudioPlayer(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _uiState.update { s -> s.copy(isPlaying = false, currentPositionMs = 0) }
                }
            }
            val dur = mediaPlayer?.duration ?: 0
            _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0, durationMs = dur) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        try {
            if (player.isPlaying) {
                player.pause()
                stopPlaybackTracker()
                _uiState.update { it.copy(isPlaying = false) }
            } else {
                player.start()
                startPlaybackTracker()
                _uiState.update { it.copy(isPlaying = true) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        try {
            player.seekTo(positionMs)
            _uiState.update { it.copy(currentPositionMs = positionMs) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPlaybackTracker() {
        stopPlaybackTracker()
        playbackTrackerJob = viewModelScope.launch {
            while (isActive) {
                val current = mediaPlayer?.currentPosition ?: 0
                _uiState.update { it.copy(currentPositionMs = current) }
                delay(300)
            }
        }
    }

    private fun stopPlaybackTracker() {
        playbackTrackerJob?.cancel()
        playbackTrackerJob = null
    }
}
