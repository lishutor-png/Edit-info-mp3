package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
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
import com.example.mp3.SampleMp3Helper
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

data class UiState(
    val metadata: Mp3Metadata? = null,
    val sourceFile: File? = null,
    val isSampleMode: Boolean = true,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val totalDurationMs: Int = 0,
    val statusMessage: String? = null,
    val exportedSuccessUri: Uri? = null,
    val exportedFileName: String? = null,
    val showExportSuccessDialog: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    init {
        // Auto-load sample song on first launch for instant testing
        loadSampleMp3()
    }

    fun loadSampleMp3() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Memuat lagu contoh...") }
            try {
                val file = withContext(Dispatchers.IO) {
                    SampleMp3Helper.createSampleMp3File(getApplication())
                }
                loadFromFile(file, isSample = true)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false, statusMessage = "Gagal memuat lagu contoh: ${e.localizedMessage}") }
            }
        }
    }

    fun loadMp3FromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, statusMessage = "Membaca file MP3...") }
            try {
                val context = getApplication<Application>()
                val resolvedName = getFileNameFromUri(context, uri) ?: "audio_${System.currentTimeMillis()}.mp3"
                val tempFile = File(context.cacheDir, "current_editing.mp3")

                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                loadFromFile(tempFile, isSample = false, resolvedName = resolvedName)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(isLoading = false, statusMessage = "Gagal membuka file: ${e.localizedMessage}") }
            }
        }
    }

    private suspend fun loadFromFile(file: File, isSample: Boolean = false, resolvedName: String? = null) {
        stopAudio()
        val context = getApplication<Application>()
        val meta = withContext(Dispatchers.IO) {
            val extracted = Mp3Id3Engine.extractMetadata(context, file)
            if (resolvedName != null) {
                extracted.copy(fileName = resolvedName)
            } else {
                extracted
            }
        }

        prepareAudioPlayer(file)

        _uiState.update {
            it.copy(
                metadata = meta,
                sourceFile = file,
                isSampleMode = isSample,
                isLoading = false,
                statusMessage = if (isSample) "Lagu contoh siap diedit." else "File MP3 Anda berhasil dimuat."
            )
        }
    }

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
            val bytes = ImageUtils.compressBitmapToCoverBytes(bitmap, quality = 88, maxDimension = 1000)
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
                val safeFileName = "${targetTitle} - ${targetArtist}.mp3"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")

                // Keep local cache copy of exported file for immediate in-app player preview
                val exportedCacheFile = File(context.cacheDir, "exported_preview.mp3")

                val resultUri: Uri? = withContext(Dispatchers.IO) {
                    // Export to cache file first to ensure complete write and instant in-app playback
                    FileOutputStream(exportedCacheFile).use { out ->
                        Mp3Id3Engine.exportMp3(source, meta, out)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Delete previous duplicate with exact same display name if any
                        try {
                            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                            val selectionArgs = arrayOf(safeFileName, "%LishEdInfo%")
                            context.contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
                        } catch (ignored: Exception) {}

                        val contentValues = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                            put(MediaStore.Audio.Media.TITLE, targetTitle)
                            put(MediaStore.Audio.Media.ARTIST, targetArtist)
                            put(MediaStore.Audio.Media.ALBUM, meta.album)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LishEdInfo")
                            put(MediaStore.Audio.Media.IS_PENDING, 1)
                        }

                        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                                exportedCacheFile.inputStream().use { input ->
                                    input.copyTo(out)
                                }
                            }
                            contentValues.clear()
                            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, contentValues, null, null)

                            // Trigger MediaScanner to parse new tags and album art thumbnail into MediaStore
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
                        }
                        uri
                    } else {
                        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                        val outFolder = File(musicDir, "LishEdInfo").apply { mkdirs() }
                        val outFile = File(outFolder, safeFileName)
                        exportedCacheFile.copyTo(outFile, overwrite = true)
                        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("audio/mpeg"), null)
                        Uri.fromFile(outFile)
                    }
                }

                if (resultUri != null) {
                    // Update player with newly exported file so user can test it right now
                    prepareAudioPlayer(exportedCacheFile)

                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            sourceFile = exportedCacheFile,
                            statusMessage = "Berhasil disimpan! Lagu siap diputar di pemutar musik.",
                            exportedSuccessUri = resultUri,
                            exportedFileName = safeFileName,
                            showExportSuccessDialog = true
                        )
                    }
                } else {
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
                val exportedCacheFile = File(context.cacheDir, "exported_preview.mp3")

                withContext(Dispatchers.IO) {
                    FileOutputStream(exportedCacheFile).use { out ->
                        Mp3Id3Engine.exportMp3(source, meta, out)
                    }
                    context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
                        exportedCacheFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }

                // Update player with newly exported file
                prepareAudioPlayer(exportedCacheFile)

                val targetTitle = meta.title.ifBlank { "Lagu" }.trim()
                val fileName = getFileNameFromUri(context, destinationUri) ?: "${targetTitle}.mp3"

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        sourceFile = exportedCacheFile,
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
                    stopProgressTracking()
                }
            }
            val dur = mediaPlayer?.duration ?: 0
            _uiState.update { it.copy(totalDurationMs = dur, currentPositionMs = 0, isPlaying = false) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            stopProgressTracking()
            _uiState.update { it.copy(isPlaying = false) }
        } else {
            player.start()
            startProgressTracking()
            _uiState.update { it.copy(isPlaying = true) }
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _uiState.update { it.copy(currentPositionMs = player.currentPosition) }
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun stopAudio() {
        stopProgressTracking()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0) }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { p ->
                val cut = p.lastIndexOf('/')
                if (cut != -1) p.substring(cut + 1) else p
            }
        }
        return name
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
