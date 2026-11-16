package com.chunkcompress.app.ui.screens

import androidx.compose.foundation.background
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
import com.chunkcompress.app.core.ConflictStrategy
import com.chunkcompress.app.core.ManifestManager
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.ui.theme.*
import com.chunkcompress.app.ui.viewmodel.MainViewModel

@Composable
fun DecompressScreen(
    viewModel: MainViewModel,
    onPickChunksFolder: () -> Unit,
    onPickDestFolder: () -> Unit,
    onStartDecompression: () -> Unit
) {
    val sourceDir by viewModel.decompressSourceDir.collectAsState()
    val destDir by viewModel.decompressDestDir.collectAsState()
    val conflictStrategy by viewModel.conflictStrategy.collectAsState()
    val deleteChunks by viewModel.deleteChunksOnExtract.collectAsState()
    val verifyOnly by viewModel.verifyOnlyMode.collectAsState()

    var showConfirmDeleteChunkDialog by remember { mutableStateOf(false) }

    val manifest = remember(sourceDir) {
        sourceDir?.let { ManifestManager.loadManifest(it) }
    }

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
                Icon(Icons.Default.Unarchive, contentDescription = null, tint = AccentCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Decompress & Restore",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Zero-loss recovery with Zip-Slip protection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // Folder selection
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
                    icon = Icons.Default.FolderZip,
                    label = "Chunks Directory (with chunk_*.zip)",
                    path = sourceDir?.absolutePath ?: "Tap to choose directory with chunks...",
                    extraInfo = if (manifest != null) "Manifest valid: ${manifest.chunks.size} chunks, ${StorageUtils.formatBytes(manifest.totalOriginalBytes)}" else null,
                    onClick = onPickChunksFolder
                )

                if (!verifyOnly) {
                    FolderSelectorRow(
                        icon = Icons.Default.FolderOpen,
                        label = "Restore Destination Folder",
                        path = destDir?.absolutePath ?: "Tap to choose destination folder...",
                        extraInfo = null,
                        onClick = onPickDestFolder
                    )
                }
            }
        }

        // Manifest Preview Card
        if (manifest != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Manifest Details",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Source Folder: ${manifest.sourceFolderName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Total Files: ${manifest.totalFiles} (${StorageUtils.formatBytes(manifest.totalOriginalBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "Chunks: ${manifest.chunks.size} archives (${StorageUtils.formatBytes(manifest.targetChunkBytes)} target chunk)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentCyan
                    )
                }
            }
        }

        // Conflict Resolution Strategy
        if (!verifyOnly) {
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
                        text = "2. File Conflict Handling",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Pair("Auto Rename", ConflictStrategy.RENAME),
                            Pair("Overwrite", ConflictStrategy.OVERWRITE),
                            Pair("Skip Existing", ConflictStrategy.SKIP)
                        ).forEach { (label, strat) ->
                            val selected = conflictStrategy == strat
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.setConflictStrategy(strat) },
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
        }

        // Restoration Options
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
                    text = "3. Restoration Modes",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                // Verify-Only Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Verify-Only Mode",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Validates archive checksums without extracting files to disk.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = verifyOnly,
                        onCheckedChange = { viewModel.setVerifyOnlyMode(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryBlue)
                    )
                }

                if (!verifyOnly) {
                    Divider(color = BorderColor)

                    // Low-Space Restoration Mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Low-Storage Restore Mode",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Deletes each chunk archive as soon as its files are extracted and verified.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = deleteChunks,
                            onCheckedChange = {
                                if (it) {
                                    showConfirmDeleteChunkDialog = true
                                } else {
                                    viewModel.setDeleteChunksOnExtract(false)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarningAmber, checkedTrackColor = DangerRed.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }

        val canStart = sourceDir != null && (verifyOnly || destDir != null) && manifest != null

        Button(
            onClick = onStartDecompression,
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
                text = if (verifyOnly) "Start Archive Verification" else "Start Restoration",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showConfirmDeleteChunkDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteChunkDialog = false },
            title = {
                Text(text = "Confirm Low-Storage Mode", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Low-Storage Mode deletes each chunk_*.zip file after verifying and extracting its contents.\n\nEnsure your destination has enough space and is permanent.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setDeleteChunksOnExtract(true)
                        showConfirmDeleteChunkDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text("Enable Mode")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDeleteChunkDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
