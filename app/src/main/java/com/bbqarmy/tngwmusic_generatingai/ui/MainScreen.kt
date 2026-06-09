package com.bbqarmy.tngwmusic_generatingai.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bbqarmy.tngwmusic_generatingai.audio.AudioPlayerManager
import com.bbqarmy.tngwmusic_generatingai.generator.MusicGenerationManager
import com.bbqarmy.tngwmusic_generatingai.service.MusicGenerationService
import com.bbqarmy.tngwmusic_generatingai.util.ModelProvider
import com.bbqarmy.tngwmusic_generatingai.util.TrackHistoryManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeneratedTrack(
    val file: File,
    val prompt: String,
    val seed: Long,
    val durationMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    generationManager: MusicGenerationManager,
    playerManager: AudioPlayerManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboard.current
    val modelProvider = remember { ModelProvider(context) }
    val historyManager = remember { TrackHistoryManager(context) }
    
    var prompt by rememberSaveable { mutableStateOf("") }
    var durationSeconds by rememberSaveable { mutableFloatStateOf(10f) }
    
    val trackHistory = remember { mutableStateListOf<GeneratedTrack>() }
    
    val isGenerating by generationManager.isGenerating.collectAsState()
    val isPaused by generationManager.isPaused.collectAsState()
    val generationProgress by generationManager.generationProgress.collectAsState()
    val generationStatus by generationManager.generationStatus.collectAsState()
    val isPlaying by playerManager.isPlaying.collectAsState()
    
    // Load initial history
    LaunchedEffect(Unit) {
        trackHistory.addAll(historyManager.loadHistory())
    }

    // Refresh history when generation finishes
    LaunchedEffect(isGenerating) {
        if (!isGenerating) {
            val latest = historyManager.loadHistory()
            if (latest.size != trackHistory.size) {
                trackHistory.clear()
                trackHistory.addAll(latest)
            }
        }
    }
    var trackToDelete by remember { mutableStateOf<GeneratedTrack?>(null) }
    var trackToSave by remember { mutableStateOf<GeneratedTrack?>(null) }
    
    // Advanced Settings
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var steps by rememberSaveable { mutableIntStateOf(25) }
    var cfgScale by rememberSaveable { mutableFloatStateOf(4.5f) }
    var sampler by rememberSaveable { mutableStateOf("pingpong") }
    var seedText by rememberSaveable { mutableStateOf("0") }
    
    val samplers = listOf("pingpong", "euler", "rk4", "dpmpp")
    var samplerExpanded by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isReady by remember { mutableStateOf(modelProvider.isReady()) }
    var setupStatus by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        trackToSave?.file?.inputStream()?.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    snackbarHostState.showSnackbar("Saved successfully!")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Save failed: ${e.localizedMessage}")
                } finally {
                    trackToSave = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music Gen AI") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Model Status Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Stable Audio 3.0 Small (ONNX)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isReady) {
                        Text("✅ Models Ready (Internal Storage)", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Initial Setup Required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The models need to be extracted from assets to internal storage for high-performance execution.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (setupStatus.isNotEmpty()) {
                            Text(setupStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            modelProvider.prepareModels { status ->
                                                setupStatus = status
                                            }
                                            isReady = true
                                            setupStatus = ""
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Setup failed: ${e.localizedMessage}")
                                            setupStatus = ""
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Prepare Models (One-time)")
                            }
                        }
                    }
                }
            }

            // Prompt Input
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Enter prompt (e.g., Lo-fi hip hop beat)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isGenerating && isReady
            )

            // Duration Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Duration: ${durationSeconds.toInt()}s",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Max 60s",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = durationSeconds,
                    onValueChange = { durationSeconds = it },
                    valueRange = 5f..60f,
                    steps = 10, // 5s increments: (60-5)/5 - 1 = 10 steps
                    enabled = !isGenerating && isReady
                )
            }

            // Advanced Settings Toggle
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.align(Alignment.Start)
            ) {
                Icon(
                    if (showAdvanced) Icons.Default.Pause else Icons.Default.Refresh, // Reusing icons
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (showAdvanced) "Hide Advanced Settings" else "Show Advanced Settings")
            }

            if (showAdvanced) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Steps
                        Column {
                            Text("Steps: $steps", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = steps.toFloat(),
                                onValueChange = { steps = it.toInt() },
                                valueRange = 1f..50f,
                                enabled = !isGenerating
                            )
                        }

                        // CFG Scale
                        Column {
                            Text("CFG Scale: ${"%.1f".format(Locale.ROOT, cfgScale)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = cfgScale,
                                onValueChange = { cfgScale = it },
                                valueRange = 0.5f..8.0f,
                                enabled = !isGenerating
                            )
                        }

                        // Sampler
                        Column {
                            Text("Sampler", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { samplerExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isGenerating
                                ) {
                                    Text(sampler)
                                }
                                DropdownMenu(
                                    expanded = samplerExpanded,
                                    onDismissRequest = { samplerExpanded = false }
                                ) {
                                    samplers.forEach { s ->
                                        DropdownMenuItem(
                                            text = { Text(s) },
                                            onClick = {
                                                sampler = s
                                                samplerExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Seed
                        OutlinedTextField(
                            value = seedText,
                            onValueChange = { if (it.isEmpty() || it.toLongOrNull() != null) seedText = it },
                            label = { Text("Seed (0 = random)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isGenerating,
                            singleLine = true
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val seed = seedText.toLongOrNull() ?: 0L
                    val intent = Intent(context, MusicGenerationService::class.java).apply {
                        putExtra("prompt", prompt)
                        putExtra("modelDir", modelProvider.modelDir.absolutePath)
                        putExtra("duration", durationSeconds)
                        putExtra("steps", steps)
                        putExtra("cfgScale", cfgScale)
                        putExtra("sampler", sampler)
                        putExtra("seed", seed)
                    }
                    ContextCompat.startForegroundService(context, intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotEmpty() && !isGenerating && isReady
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating...")
                } else {
                    Text("Generate Music")
                }
            }

            // Generation Progress
            if (isGenerating || (generationProgress > 0 && generationProgress < 1f) || generationStatus.startsWith("Error")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isPaused) "Paused: $generationStatus" else generationStatus,
                        fontWeight = FontWeight.Medium,
                        color = if (generationStatus.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (!generationStatus.startsWith("Error")) {
                        LinearProgressIndicator(
                            progress = { generationProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Text("${(generationProgress * 100).toInt()}%")
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (isPaused) generationManager.resumeGeneration()
                                    else generationManager.pauseGeneration()
                                }
                            ) {
                                Icon(
                                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isPaused) "Resume" else "Pause")
                            }
                            
                            Button(
                                onClick = { generationManager.stopGeneration() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Stop")
                            }
                        }
                    }
                }
            }

            // Track History
            trackHistory.forEach { track ->
                TrackPlayerCard(
                    track = track,
                    playerManager = playerManager,
                    clipboard = clipboard,
                    onSave = { 
                        trackToSave = track
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        saveLauncher.launch("tngwmusic_$timestamp.wav")
                    },
                    onDelete = { trackToDelete = track },
                    snackbarHostState = snackbarHostState,
                    scope = scope
                )
            }
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                withFrameMillis { 
                    playerManager.updateProgress()
                }
            }
        }
    }

    if (trackToDelete != null) {
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete Track") },
            text = { Text("Are you sure you want to delete this track? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            trackToDelete?.let { track ->
                                if (track.file.exists()) track.file.delete()
                                trackHistory.remove(track)
                                historyManager.saveHistory(trackHistory)
                                if (playerManager.currentFile.value == track.file) {
                                    playerManager.releasePlayer()
                                }
                            }
                            trackToDelete = null
                            snackbarHostState.showSnackbar("Track deleted")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TrackPlayerCard(
    track: GeneratedTrack,
    playerManager: AudioPlayerManager,
    clipboard: Clipboard,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val isPlaying by playerManager.isPlaying.collectAsState()
    val duration by playerManager.duration.collectAsState()
    val currentFile by playerManager.currentFile.collectAsState()
    
    val isThisTrackActive = currentFile == track.file

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isThisTrackActive) 
                MaterialTheme.colorScheme.tertiaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.Default.MusicNote, 
                        contentDescription = null,
                        tint = if (isThisTrackActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val timestamp = remember(track.file) {
                        val date = Date(track.file.lastModified())
                        SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(date)
                    }
                    Text(
                        timestamp,
                        fontWeight = FontWeight.Bold,
                        color = if (isThisTrackActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }
            }

            // Display Used Prompt and Seed
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Prompt: ${track.prompt}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        scope.launch { 
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("prompt", track.prompt)))
                            snackbarHostState.showSnackbar("Prompt copied") 
                        }
                    }
                )
                Text(
                    text = "Seed: ${if (track.seed == 0L) "Random" else track.seed}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.clickable {
                        scope.launch { 
                            val seedStr = if (track.seed == 0L) "0" else track.seed.toString()
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("seed", seedStr)))
                            snackbarHostState.showSnackbar("Seed copied") 
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (isThisTrackActive) {
                if (duration > 0 || track.durationMs > 0) {
                    val displayDuration = if (duration > 0) duration else track.durationMs
                    val pos by playerManager.currentPosition.collectAsState()
                    
                    Slider(
                        value = pos.toFloat(),
                        onValueChange = { playerManager.seekTo(it.toLong()) },
                        valueRange = 0f..displayDuration.toFloat().coerceAtLeast(1f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(pos), fontSize = 12.sp)
                        Text(formatTime(displayDuration), fontSize = 12.sp)
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                // Show total duration when not active
                val displayDuration = if (track.durationMs > 0) {
                    track.durationMs
                } else {
                    // Recalculate if it was somehow stored as 0
                    if (track.file.exists()) ((track.file.length() - 44) * 1000.0 / 176400.0).toLong() else 0L
                }
                
                Text(
                    text = formatTime(displayDuration),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            FilledIconButton(
                onClick = { 
                    if (isThisTrackActive) {
                        playerManager.togglePlayPause()
                    } else {
                        playerManager.playAudio(track.file)
                    }
                },
                modifier = Modifier.size(if (isThisTrackActive) 56.dp else 48.dp),
                colors = if (isThisTrackActive) 
                    IconButtonDefaults.filledIconButtonColors() 
                else 
                    IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(
                    if (isThisTrackActive && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(if (isThisTrackActive) 32.dp else 24.dp)
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
