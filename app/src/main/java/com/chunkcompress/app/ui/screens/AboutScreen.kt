package com.chunkcompress.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunkcompress.app.ui.theme.*

@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
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
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "About ChunkCompress",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Version 2.0.0 (Production Build)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentCyan
                )
            }
        }

        // Summary Card
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
                    text = "The Low-Storage Architecture",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Standard compression tools require simultaneous free disk space equal to the entire output archive plus the original files (e.g., 50 GB uncompressed requires up to 50 GB + 25 GB free space).\n\n" +
                            "ChunkCompress solves this with an incremental transactional pipeline: it compresses files into user-defined chunk targets (e.g. 1 GB, 2 GB), strictly verifies the uncompressed stream against SHA-256 checksums in memory, and only upon 100% verification reclaims original file storage to enable the next chunk.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    lineHeight = 22.sp
                )
            }
        }

        // Safety Highlights
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Core Safety Guarantees",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                SafetyBullet("Zero-Data-Loss Default", "Original file deletion is disabled by default. If enabled, it only deletes files that pass 100% in-memory SHA-256 comparison.")
                SafetyBullet("Zip-Slip & Path Traversal Guard", "Decompression validates canonical destination paths to protect against malicious archive path escapes.")
                SafetyBullet("Crash & Interruption Resumability", "Maintains an atomic versioned chunk_manifest.json with backup rollback to resume seamlessly after unexpected power-loss or app restart.")
                SafetyBullet("Dynamic Low-Space Pause", "Continuously checks disk headroom and pauses automatically at 300 MB reserve to avoid system disk full crashes.")
            }
        }

        // Legal & Open Source
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "License & Credits", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(text = "ChunkCompress is open source under the MIT License.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text(text = "Built with Jetpack Compose, Kotlin Coroutines, and Java Streaming NIO.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SafetyBullet(title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(text = desc, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
