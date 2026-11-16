package com.chunkcompress.app.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.ui.theme.*
import java.io.File

@Composable
fun FolderBrowserDialog(
    initialDir: File? = null,
    title: String = "Select Folder",
    onFolderSelected: (File) -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember {
        mutableStateOf(
            if (initialDir != null && initialDir.exists() && initialDir.isDirectory) {
                initialDir
            } else {
                Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            }
        )
    }

    val filesAndDirs = remember(currentDir) {
        try {
            val list = currentDir.listFiles() ?: emptyArray()
            // Sort: directories first, then alphabetically
            list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Title and Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // Quick jump chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val quickLocations = listOf(
                        Pair("Root", Environment.getExternalStorageDirectory()),
                        Pair("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
                        Pair("DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)),
                        Pair("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                    )
                    quickLocations.forEach { (label, file) ->
                        if (file != null && file.exists()) {
                            AssistChip(
                                onClick = { currentDir = file },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = DarkSurfaceVariant,
                                    labelColor = AccentCyan
                                )
                            )
                        }
                    }
                }

                // Current Path Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBackground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val parent = currentDir.parentFile
                            if (parent != null && parent.canRead()) {
                                currentDir = parent
                            }
                        },
                        enabled = currentDir.parentFile != null && currentDir.parentFile?.canRead() == true,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = AccentCyan)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }

                // File/Folder List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkBackground)
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                ) {
                    if (filesAndDirs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Empty folder or no permission", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filesAndDirs) { item ->
                                val isDir = item.isDirectory
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isDir && item.canRead()) {
                                                currentDir = item
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (isDir) AccentCyan else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isDir) TextPrimary else TextSecondary,
                                            maxLines = 1
                                        )
                                        if (!isDir) {
                                            Text(
                                                text = StorageUtils.formatBytes(item.length()),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (isDir) {
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BorderColor, modifier = Modifier.size(16.dp))
                                    }
                                }
                                Divider(color = BorderColor.copy(alpha = 0.4f), thickness = 0.5.dp)
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onFolderSelected(currentDir)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select This Folder", maxLines = 1)
                    }
                }
            }
        }
    }
}
