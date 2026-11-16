package com.chunkcompress.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunkcompress.app.core.SessionRecord
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.ui.theme.*
import com.chunkcompress.app.ui.viewmodel.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    onResumeSession: (SessionRecord) -> Unit
) {
    val context = LocalContext.current
    val history by viewModel.historySessions.collectAsState()
    var selectedSession by remember { mutableStateOf<SessionRecord?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = AccentCyan)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Session History",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "${history.size} recorded operations",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            if (history.isNotEmpty()) {
                IconButton(onClick = { showClearConfirmDialog = true }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear History", tint = DangerRed)
                }
            }
        }

        // List
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.HistoryToggleOff, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No past sessions recorded yet.", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { session ->
                    SessionCard(
                        session = session,
                        onClick = { selectedSession = session }
                    )
                }
            }
        }
    }

    // Session Details Dialog
    if (selectedSession != null) {
        val session = selectedSession!!
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.timestamp))

        AlertDialog(
            onDismissRequest = { selectedSession = null },
            title = {
                Text(
                    text = if (session.isDecompression) "Decompression Details" else "Compression Details",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Date: $dateStr", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text(text = "Status: ${session.statusText}", color = if (session.isCompleted) SuccessGreen else WarningAmber, fontWeight = FontWeight.Bold)
                    Text(text = "Source: ${session.sourcePath}", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Text(text = "Destination: ${session.destinationPath}", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    Divider(color = BorderColor)
                    Text(text = "Original Size: ${StorageUtils.formatBytes(session.totalOriginalBytes)}", color = TextPrimary)
                    Text(text = "Compressed Size: ${StorageUtils.formatBytes(session.compressedSizeBytes)}", color = TextPrimary)
                    Text(text = "Storage Reclaimed: ${StorageUtils.formatBytes(session.reclaimedBytes)}", color = SuccessGreen, fontWeight = FontWeight.Bold)
                    Text(text = "Files: ${session.totalFiles} across ${session.totalChunks} chunks", color = TextSecondary)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val report = viewModel.getSessionReport(session)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Session Report", report))
                            Toast.makeText(context, "Report copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Report")
                    }

                    Button(
                        onClick = { selectedSession = null },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text("Close")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteHistoryRecord(session.sessionId)
                        selectedSession = null
                    }
                ) {
                    Text("Delete Record", color = DangerRed)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Clear All Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear All History?", color = TextPrimary) },
            text = { Text("This will remove all session records from ChunkCompress history. Your actual archive files on disk will NOT be deleted.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun SessionCard(
    session: SessionRecord,
    onClick: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(session.timestamp))

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (session.isDecompression) Icons.Default.Unarchive else Icons.Default.Archive,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = File(session.sourcePath).name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = "${StorageUtils.formatBytes(session.totalOriginalBytes)} • ${session.totalChunks} chunks • ${if (session.reclaimedBytes > 0) "+" + StorageUtils.formatBytes(session.reclaimedBytes) + " freed" else session.statusText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.isCompleted) SuccessGreen else WarningAmber
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
