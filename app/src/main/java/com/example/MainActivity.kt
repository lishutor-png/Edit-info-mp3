package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mp3.ImageUtils
import com.example.ui.theme.LishEdInfoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming intent (e.g. "Open with" from File Manager)
        intent?.data?.let { uri ->
            viewModel.loadAudioFromUri(uri)
        }

        setContent {
            LishEdInfoTheme {
                MainScreen(
                    viewModel = viewModel,
                    onOpenDefaultPlayer = { uri -> openInDefaultPlayer(uri) }
                )
            }
        }
    }

    private fun openInDefaultPlayer(uri: Uri) {
        try {
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(playIntent, "Putar dengan Pemutar Musik"))
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada pemutar musik yang tersedia: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenDefaultPlayer: (Uri) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadAudioFromUri(it) }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = ImageUtils.decodeUriToBitmap(context, it, maxDimension = 600)
            if (bitmap != null) {
                viewModel.updateCoverImage(bitmap)
            } else {
                Toast.makeText(context, "Gagal memproses gambar cover", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportToDestinationUri(it) }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "App Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LishEdInfo MP3",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pickAudioLauncher.launch("audio/mpeg") },
                        modifier = Modifier.testTag("action_pick_file")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Pilih File MP3",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (uiState.sourceFile == null) {
                // Empty state
                EmptyStateCard(onPickFile = { pickAudioLauncher.launch("audio/mpeg") })
            } else {
                // Loaded file UI
                CoverImageSection(
                    coverBitmap = uiState.metadata?.coverBitmap,
                    onPickImage = { pickImageLauncher.launch("image/*") },
                    onRemoveImage = { viewModel.removeCoverImage() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                AudioPlayerCard(
                    isPlaying = uiState.isPlaying,
                    currentPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                MetadataEditorSection(
                    metadata = uiState.metadata,
                    onTitleChange = { viewModel.updateTitle(it) },
                    onArtistChange = { viewModel.updateArtist(it) },
                    onAlbumChange = { viewModel.updateAlbum(it) },
                    onGenreChange = { viewModel.updateGenre(it) },
                    onYearChange = { viewModel.updateYear(it) },
                    onTrackChange = { viewModel.updateTrackNumber(it) },
                    onAutoFill = { viewModel.autoFillFromFileName() }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                ActionButtonsSection(
                    isExporting = uiState.isExporting,
                    onSaveToMusicFolder = { viewModel.saveToMusicFolder() },
                    onExportCustom = {
                        val title = uiState.metadata?.title?.ifBlank { "Lagu" } ?: "Lagu"
                        val artist = uiState.metadata?.artist?.ifBlank { "Artis" } ?: "Artis"
                        exportFileLauncher.launch("${title} - ${artist}.mp3")
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Success Dialog
    if (uiState.showExportSuccessDialog && uiState.exportedSuccessUri != null) {
        ExportSuccessDialog(
            fileName = uiState.exportedFileName,
            onDismiss = { viewModel.dismissSuccessDialog() },
            onPlayNow = {
                uiState.exportedSuccessUri?.let { uri ->
                    onOpenDefaultPlayer(uri)
                }
                viewModel.dismissSuccessDialog()
            }
        )
    }

    // Loading Overlay
    if (uiState.isLoading || uiState.isExporting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (uiState.isExporting) "Menyimpan ke Pemutar Musik..." else "Membaca data...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard(onPickFile: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Pilih File MP3",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Edit judul lagu, nama artis, album, dan cover gambar agar tampil rapi di pemutar musik HP Anda.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onPickFile,
                modifier = Modifier.testTag("button_select_mp3"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pilih MP3 Sekarang")
            }
        }
    }
}

@Composable
fun CoverImageSection(
    coverBitmap: android.graphics.Bitmap?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPickImage() },
            contentAlignment = Alignment.Center
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = "Cover Album",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Tambah Sampul",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ketuk untuk Tambah Sampul",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPickImage,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("button_change_cover")
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (coverBitmap != null) "Ganti Cover" else "Tambah Cover", fontSize = 12.sp)
            }
            if (coverBitmap != null) {
                OutlinedButton(
                    onClick = onRemoveImage,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("button_remove_cover")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Hapus", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun AudioPlayerCard(
    isPlaying: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .testTag("button_play_pause")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Jeda" else "Putar",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { onSeek(it.toInt()) },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(currentPositionMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatTime(durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataEditorSection(
    metadata: com.example.mp3.Mp3Metadata?,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onGenreChange: (String) -> Unit,
    onYearChange: (String) -> Unit,
    onTrackChange: (String) -> Unit,
    onAutoFill: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Informasi Lagu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = onAutoFill,
                    modifier = Modifier.testTag("button_autofill")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Isi Otomatis", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = metadata?.title ?: "",
                onValueChange = onTitleChange,
                label = { Text("Judul Lagu") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_title"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = metadata?.artist ?: "",
                onValueChange = onArtistChange,
                label = { Text("Nama Artis / Penyanyi") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_artist"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = metadata?.album ?: "",
                onValueChange = onAlbumChange,
                label = { Text("Album") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_album"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = metadata?.genre ?: "",
                    onValueChange = onGenreChange,
                    label = { Text("Genre") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_genre"),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = metadata?.year ?: "",
                    onValueChange = onYearChange,
                    label = { Text("Tahun") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_year"),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Genre selection chips
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Pilih Genre Cepat (Tinggal Klik):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val popularGenres = listOf(
                        "Pop", "Dangdut", "Rock", "Akustik", "R&B", "Jazz",
                        "Hip Hop", "Indie", "Religi", "K-Pop", "EDM", "Metal",
                        "Campursari", "Blues", "Klasik", "Lo-Fi", "Reggae", "Folk"
                    )
                    items(popularGenres) { genreName ->
                        val isSelected = metadata?.genre.equals(genreName, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) onGenreChange("") else onGenreChange(genreName)
                            },
                            label = { Text(genreName, fontSize = 12.sp) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("chip_genre_$genreName")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = metadata?.trackNumber ?: "",
                onValueChange = onTrackChange,
                label = { Text("Nomor Trek") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_track"),
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun ActionButtonsSection(
    isExporting: Boolean,
    onSaveToMusicFolder: () -> Unit,
    onExportCustom: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onSaveToMusicFolder,
            enabled = !isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("button_save_music_folder"),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Simpan ke Pemutar Musik HP",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedButton(
            onClick = onExportCustom,
            enabled = !isExporting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("button_export_saf"),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ekspor ke Folder Tertentu")
        }
    }
}

@Composable
fun ExportSuccessDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                text = "Tersimpan di Pemutar Musik!",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "File telah diindeks ke folder Musik HP. Cover dan data lagu siap diputar di pemutar musik bawaan maupun aplikasi pihak ketiga.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onPlayNow,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("dialog_button_play_now")
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Putar Sekarang")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_button_dismiss")
            ) {
                Text("Tutup")
            }
        }
    )
}

fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
