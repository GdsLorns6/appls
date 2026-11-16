package com.chunkcompress.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.ui.components.FolderSelectorRow
import com.chunkcompress.app.ui.theme.*
import com.chunkcompress.app.ui.viewmodel.MainViewModel

@Composable
fun CompressScreen(
    viewModel: MainViewModel,
    onPickSourceFolder: () -> Unit,
    onPickDestFolder: () -> Unit,
    onStartCompression: () -> Unit
) {
    val freeSpace by viewModel.freeDiskSpace.collectAsState()
    val sourceDir by viewModel.sourceDirectory.collectAsState()
    val sourceStats by viewModel.sourceFolderStats.collectAsState()
    val isScanning by viewModel.isScanningSource.collectAsState()
    val destDir by viewModel.destinationDirectory.collectAsState()
    val chunkSize by viewModel.chunkSizeBytes.collectAsState()
    val compressionLevel by viewModel.compressionLevel.collectAsState()
    val verifySha256 by viewModel.verifySha256.collectAsState()
    val deleteSource by viewModel.deleteSourceAfterVerified.collectAsState()
    val reserveBytes by viewModel.emergencyReserveBytes.collectAsState()
    val preFlightReport by viewModel.preFlightReport.collectAsState()

    var showConfirmDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Archive, contentDescription = null, tint = AccentCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Compression Setup",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Safe incremental chunk processing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // 1. Folders Selection
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "1. Locations",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                FolderSelectorRow(
                    icon = Icons.Default.Folder,
                    label = "Source Folder (to compress)",
                    path = sourceDir?.absolutePath ?: "Tap to choose 50 GB folder...",
                    extraInfo = if (isScanning) "Analyzing folder contents..." else if (sourceDir != null) "${StorageUtils.formatBytes(sourceStats.first)} across ${sourceStats.second} files" else null,
                    onClick = onPickSourceFolder
                )

                FolderSelectorRow(
                    icon = Icons.Default.SaveAlt,
                    label = "Output Directory (where chunks will be written)",
                    path = destDir?.absolutePath ?: "Tap to choose output directory...",
                    extraInfo = "Free space on destination: ${StorageUtils.formatBytes(freeSpace)}",
                    onClick = onPickDestFolder
                )
            }
        }

        // 2. Chunk Target Size & Auto Recommendation
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "2. Chunk Target Size",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = StorageUtils.formatBytes(chunkSize),
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentCyan,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Files are batched up to this size. When source deletion is enabled, storage is reclaimed after each verified chunk.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        Pair("500 MB", 500L * 1024 * 1024),
                        Pair("1 GB", 1024L * 1024 * 1024),
                        Pair("2 GB", 2048L * 1024 * 1024),
                        Pair("5 GB", 5120L * 1024 * 1024)
                    )
                    presets.forEach { (label, bytes) ->
                        val selected = chunkSize == bytes
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setChunkSize(bytes) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryBlue,
                                selectedLabelColor = Color.White,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.autoRecommendChunkSize() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto-Recommend Based on Free Space")
                }
            }
        }

        // 3. Compression Level
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. Compression Method",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf(
                        Pair("Store (0)", 0),
                        Pair("Fast (1)", 1),
                        Pair("Balanced (6)", 6),
                        Pair("Max (9)", 9)
                    )
                    levels.forEach { (label, lvl) ->
                        val selected = compressionLevel == lvl
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setCompressionLevel(lvl) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PrimaryBlue,
                                selectedLabelColor = Color.White,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }

        // 4. Pre-Flight Safety Report
        if (preFlightReport != null && sourceDir != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (preFlightReport?.isSafeToProceed == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (preFlightReport?.isSafeToProceed == true) SuccessGreen else WarningAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (preFlightReport?.isSafeToProceed == true) "Pre-Flight Check: Safe to Proceed" else "Pre-Flight Notice",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (preFlightReport?.isSafeToProceed == true) SuccessGreen else WarningAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (preFlightReport?.canResume == true) {
                        Text(
                            text = "• An existing session was found with ${preFlightReport?.resumedCompletedChunks} verified chunks. ChunkCompress will resume without restarting!",
                            color = AccentCyan,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    preFlightReport?.warnings?.forEach { warning ->
                        Text(
                            text = "• $warning",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarningAmber
                        )
                    }

                    Text(
                        text = "Headroom: ${StorageUtils.formatBytes(freeSpace)} free / ${StorageUtils.formatBytes(chunkSize + reserveBytes)} recommended.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // 5. Data Safety Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "4. Data Protection Rules",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                // Verify SHA-256 Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Strict SHA-256 Integrity Verification",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Reads back and verifies checksum of every file in the archive before any action.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = verifySha256,
                        onCheckedChange = { viewModel.setVerifySha256(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryBlue)
                    )
                }

                Divider(color = BorderColor)

                // Delete Source Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Delete Verified Source Files",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (deleteSource) DangerRed else TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Disabled by default. When enabled, original files are deleted ONLY after 100% verification passes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = deleteSource,
                        onCheckedChange = {
                            if (it) {
                                showConfirmDeleteDialog = true
                            } else {
                                viewModel.setDeleteSourceAfterVerified(false)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = WarningAmber, checkedTrackColor = DangerRed.copy(alpha = 0.5f))
                    )
                }
            }
        }

        // Action Button
        val canStart = sourceDir != null && sourceDir!!.exists() && !isScanning

        Button(
            onClick = {
                onStartCompression()
            },
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryBlue,
                disabledContainerColor = DarkSurfaceVariant
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (preFlightReport?.canResume == true) "Resume Compression Session" else "Start Safe Incremental Compression",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showConfirmDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteDialog = false },
            title = {
                Text(text = "Confirm Source Deletion", color = DangerRed, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "You are enabling Source File Deletion to reclaim storage.\n\nChunkCompress guarantees that NO file will be deleted unless its uncompressed contents match its SHA-256 checksum 100% inside the archive, and its file attributes have not changed.\n\nDo you wish to enable source file deletion?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setDeleteSourceAfterVerified(true)
                        showConfirmDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("Enable Source Deletion")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteDialog = false }) {
                    Text("Keep Disabled (Safer)", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
