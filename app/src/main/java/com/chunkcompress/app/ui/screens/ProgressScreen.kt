package com.chunkcompress.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunkcompress.app.core.EngineState
import com.chunkcompress.app.core.LogLevel
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.ui.theme.*
import com.chunkcompress.app.ui.viewmodel.MainViewModel

@Composable
fun ProgressScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val snapshot by viewModel.progressFlow.collectAsState()
    val state = snapshot.state

    val listState = rememberLazyListState()
    var selectedLogLevelFilter by remember { mutableStateOf<LogLevel?>(null) } // null = All

    val filteredLogs = remember(snapshot.logs, selectedLogLevelFilter) {
        if (selectedLogLevelFilter == null) snapshot.logs
        else snapshot.logs.filter { it.level == selectedLogLevelFilter }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    val isPaused = state is EngineState.PausedUser || state is EngineState.PausedLowStorage
    val isFinished = state is EngineState.Completed || state is EngineState.Error || state is EngineState.Cancelled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Live Execution Dashboard",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (snapshot.isResumed) {
                        Text(
                            text = "Resumed Session",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentCyan
                        )
                    }
                }
            }

            if (isFinished) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Done")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isPaused) {
                        Button(
                            onClick = { viewModel.resumeOperation() },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.pauseOperation() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningAmber)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.cancelOperation() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }

        // Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val stateTitle = when (state) {
                    is EngineState.Scanning -> "Scanning folder files..."
                    is EngineState.Planning -> "Organizing files into chunks..."
                    is EngineState.Compressing -> "Compressing Chunk ${state.chunkIndex} of ${state.totalChunks}"
                    is EngineState.Verifying -> "Verifying Chunk ${state.chunkIndex} (SHA-256 Checksum)"
                    is EngineState.DeletingSource -> "Safely deleting verified files to reclaim space"
                    is EngineState.Extracting -> "Extracting Chunk ${state.chunkIndex} of ${state.totalChunks}"
                    is EngineState.PausedLowStorage -> "Paused: Waiting for storage headroom (< 300MB left)"
                    is EngineState.PausedUser -> "Paused by user"
                    is EngineState.Completed -> "Task Completed Successfully!"
                    is EngineState.Error -> "Task Failed: ${state.message}"
                    is EngineState.Cancelled -> "Task Cancelled"
                    else -> "Idle"
                }

                val stateColor = when (state) {
                    is EngineState.Completed -> SuccessGreen
                    is EngineState.Error -> DangerRed
                    is EngineState.Verifying -> WarningAmber
                    is EngineState.DeletingSource -> SuccessGreen
                    is EngineState.PausedLowStorage, is EngineState.PausedUser -> WarningAmber
                    else -> AccentCyan
                }

                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = stateColor,
                    fontWeight = FontWeight.Bold
                )

                if (snapshot.currentFileName.isNotEmpty()) {
                    Text(
                        text = snapshot.currentFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }

                // Dual Progress Indicators
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Overall Progress",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${(snapshot.totalProgressPercent * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = snapshot.totalProgressPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = PrimaryBlue,
                        trackColor = DarkSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Current Chunk Progress",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${(snapshot.chunkProgressPercent * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCyan
                        )
                    }
                    LinearProgressIndicator(
                        progress = snapshot.chunkProgressPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentCyan,
                        trackColor = DarkSurfaceVariant
                    )
                }
            }
        }

        // Live Performance Metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                title = "Speed",
                value = "${StorageUtils.formatBytes(snapshot.currentSpeedBytesPerSec)}/s",
                color = AccentCyan,
                icon = Icons.Default.Speed,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Reclaimed Space",
                value = StorageUtils.formatBytes(snapshot.bytesReclaimed),
                color = SuccessGreen,
                icon = Icons.Default.DeleteSweep,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "Est. Remaining",
                value = formatDuration(snapshot.estimatedRemainingMs),
                color = TextPrimary,
                icon = Icons.Default.Timer,
                modifier = Modifier.weight(1f)
            )
        }

        // Live Log Terminal with Filter Chips
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Live Log Console (${filteredLogs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedLogLevelFilter == null,
                            onClick = { selectedLogLevelFilter = null },
                            label = { Text("All", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PrimaryBlue)
                        )
                        FilterChip(
                            selected = selectedLogLevelFilter == LogLevel.SUCCESS,
                            onClick = { selectedLogLevelFilter = LogLevel.SUCCESS },
                            label = { Text("Success", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SuccessGreen)
                        )
                        FilterChip(
                            selected = selectedLogLevelFilter == LogLevel.WARNING,
                            onClick = { selectedLogLevelFilter = LogLevel.WARNING },
                            label = { Text("Warn", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WarningAmber)
                        )
                        FilterChip(
                            selected = selectedLogLevelFilter == LogLevel.ERROR,
                            onClick = { selectedLogLevelFilter = LogLevel.ERROR },
                            label = { Text("Error", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DangerRed)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF070B12))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    LazyColumn(state = listState) {
                        items(filteredLogs) { log ->
                            val color = when (log.level) {
                                LogLevel.SUCCESS -> SuccessGreen
                                LogLevel.WARNING -> WarningAmber
                                LogLevel.ERROR -> DangerRed
                                LogLevel.INFO -> TextSecondary
                            }
                            Text(
                                text = log.message,
                                color = color,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--"
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = ms / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%dh %02dm", hours, minutes)
    } else {
        String.format("%02dm %02ds", minutes, seconds)
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, fontSize = 11.sp)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}
