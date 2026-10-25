package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mp3.ImageUtils
import com.example.ui.theme.LishEdInfoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LishEdInfoTheme {
                LishEdInfoSimplifiedScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LishEdInfoSimplifiedScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Activity Result Launchers
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadMp3FromUri(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = ImageUtils.decodeSampledBitmapFromUri(context, it, 1000, 1000)
            if (bitmap != null) {
                viewModel.updateCoverImage(bitmap)
            } else {
                Toast.makeText(context, "Gagal memuat gambar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exportDocumentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/mpeg")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportToDestinationUri(it) }
    }

    // Feedback notifications
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    var showAdvancedFields by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "LishEdInfo",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Ganti Info Lagu MP3",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { audioPicker.launch("audio/*") },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .testTag("button_open_mp3")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pilih Lagu", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Ergonomic Sticky Bottom Bar for instant saving
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick custom folder export icon button
                    FilledTonalIconButton(
                        onClick = {
                            val safeName = "${uiState.metadata?.title?.ifBlank { "Lagu" } ?: "Lagu"}.mp3"
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            exportDocumentPicker.launch(safeName)
                        },
                        enabled = !uiState.isExporting && uiState.sourceFile != null,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .size(52.dp)
                            .testTag("button_export_saf")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Ekspor ke folder lain",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Main Save Button
                    Button(
                        onClick = { viewModel.saveToMusicFolder() },
                        enabled = !uiState.isExporting && uiState.sourceFile != null,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("button_save_music_folder"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (uiState.isExporting) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Menyimpan Lagu...")
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Simpan ke Musik Saya",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp)
            ) {
                // Status banner if in sample mode
                if (uiState.isSampleMode) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Mode Uji Coba (Lagu Contoh)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                TextButton(
                                    onClick = { audioPicker.launch("audio/*") },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Buka File Anda", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }

                // Unified Hero Section: Cover Art + Mini Player in a single seamless card
                item {
                    UnifiedHeroSection(
                        metadata = uiState.metadata,
                        isPlaying = uiState.isPlaying,
                        currentPosMs = uiState.currentPositionMs,
                        totalDurationMs = uiState.totalDurationMs,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSeek = { viewModel.seekTo(it) },
                        onCoverClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onRemoveCover = { viewModel.removeCoverImage() }
                    )
                }

                // Quick Tools Row (Auto-fill from filename)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.autoFillFromFileName() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("button_autofill")
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Isi Otomatis dari Nama File", style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (!uiState.isSampleMode) {
                            TextButton(
                                onClick = { viewModel.loadSampleMp3() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Coba Contoh", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Main Metadata Fields Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("card_metadata_inputs"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Title Field
                            OutlinedTextField(
                                value = uiState.metadata?.title ?: "",
                                onValueChange = { viewModel.updateTitle(it) },
                                label = { Text("Judul Lagu") },
                                placeholder = { Text("Masukkan judul lagu") },
                                leadingIcon = {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_title")
                            )

                            // Artist Field
                            OutlinedTextField(
                                value = uiState.metadata?.artist ?: "",
                                onValueChange = { viewModel.updateArtist(it) },
                                label = { Text("Artis / Penyanyi") },
                                placeholder = { Text("Nama artis atau band") },
                                leadingIcon = {
                                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_artist")
                            )

                            // Album Field
                            OutlinedTextField(
                                value = uiState.metadata?.album ?: "",
                                onValueChange = { viewModel.updateAlbum(it) },
                                label = { Text("Album") },
                                placeholder = { Text("Nama album atau single") },
                                leadingIcon = {
                                    Icon(Icons.Default.Album, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = if (showAdvancedFields) ImeAction.Next else ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                    onDone = { focusManager.clearFocus() }
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_album")
                            )

                            // Accordion for Optional Fields
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showAdvancedFields = !showAdvancedFields }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (showAdvancedFields) "Sembunyikan info tambahan" else "+ Tambah Tahun, Genre, No. Track (Opsional)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (showAdvancedFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            AnimatedVisibility(
                                visible = showAdvancedFields,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = uiState.metadata?.year ?: "",
                                            onValueChange = { viewModel.updateYear(it) },
                                            label = { Text("Tahun") },
                                            placeholder = { Text("2024") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Right) }),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("input_year")
                                        )
                                        OutlinedTextField(
                                            value = uiState.metadata?.trackNumber ?: "",
                                            onValueChange = { viewModel.updateTrackNumber(it) },
                                            label = { Text("No. Track") },
                                            placeholder = { Text("1") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("input_track")
                                        )
                                    }

                                    OutlinedTextField(
                                        value = uiState.metadata?.genre ?: "",
                                        onValueChange = { viewModel.updateGenre(it) },
                                        label = { Text("Genre Musik") },
                                        placeholder = { Text("cth: Pop, Akustik, Rock") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("input_genre")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Export Success Dialog
            if (uiState.showExportSuccessDialog) {
                ExportSuccessDialog(
                    fileName = uiState.exportedFileName ?: "Lagu.mp3",
                    onDismiss = { viewModel.dismissSuccessDialog() },
                    onPlayInApp = {
                        viewModel.dismissSuccessDialog()
                        viewModel.togglePlayPause()
                    },
                    onOpenDefaultPlayer = {
                        uiState.exportedSuccessUri?.let { uri ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "audio/mpeg")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Buka dengan Pemutar Musik"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Tidak ada pemutar musik eksternal ditemukan", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShareFile = {
                        uiState.exportedSuccessUri?.let { uri ->
                            try {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/mpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Bagikan Lagu"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Gagal membagikan: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }
}

/**
 * Unified Hero Section:
 * Combines Cover Art preview (with one-touch tap to change) and the mini audio player
 * in a clean, highly modern card.
 */
@Composable
fun UnifiedHeroSection(
    metadata: com.example.mp3.Mp3Metadata?,
    isPlaying: Boolean,
    currentPosMs: Int,
    totalDurationMs: Int,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onCoverClick: () -> Unit,
    onRemoveCover: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_hero_section"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Interactive Album Cover Art Box
                Box(
                    modifier = Modifier
                        .size(116.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                        .clickable { onCoverClick() }
                        .testTag("button_tap_cover"),
                    contentAlignment = Alignment.Center
                ) {
                    val cover = metadata?.coverBitmap
                    if (cover != null) {
                        Image(
                            bitmap = cover.asImageBitmap(),
                            contentDescription = "Sampul Lagu",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Trash overlay button
                        IconButton(
                            onClick = onRemoveCover,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                .testTag("button_remove_cover")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Hapus sampul",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ganti Sampul",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // File name & audio metadata summary
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = metadata?.title?.ifBlank { metadata.fileName } ?: "Pilih Lagu MP3",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = metadata?.artist?.ifBlank { "Artis belum diatur" } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if ((metadata?.fileSize ?: 0L) > 0) {
                            "${formatFileSize(metadata?.fileSize ?: 0L)} • MP3"
                        } else {
                            "Klik 'Pilih Lagu' untuk memulai"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Ketuk gambar untuk ganti sampul",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Integrated Audio Player Scrubber
            if (totalDurationMs > 0) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = onPlayPause,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .testTag("button_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Jeda" else "Putar",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Slider(
                                value = currentPosMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                                onValueChange = { onSeek(it.toInt()) },
                                valueRange = 0f..totalDurationMs.toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatDuration(currentPosMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = formatDuration(totalDurationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportSuccessDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onPlayInApp: () -> Unit,
    onOpenDefaultPlayer: () -> Unit,
    onShareFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Lagu Berhasil Disimpan!",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "File MP3 Anda telah diperbarui dengan metadata & sampul baru secara aman.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Aliran audio MPEG 100% terjaga dan siap diputar langsung di HP Anda.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayInApp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Putar Sekarang di LishEdInfo")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onShareFile,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bagikan")
                    }
                    OutlinedButton(
                        onClick = onOpenDefaultPlayer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Buka Pemutar")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Tutup", textAlign = TextAlign.Center)
            }
        }
    )
}

private fun formatDuration(ms: Int): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format("%.2f MB", mb)
}
