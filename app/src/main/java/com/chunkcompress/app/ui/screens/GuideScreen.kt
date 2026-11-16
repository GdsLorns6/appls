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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunkcompress.app.ui.theme.*

@Composable
fun GuideScreen() {
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
                    .background(PrimaryBlue.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.HelpOutline, contentDescription = null, tint = AccentCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "How It Works",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Understanding Low-Space Safe Compression",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        // The Problem
        GuideCard(
            title = "The Problem: The 50 GB Dilemma",
            icon = Icons.Default.SdCardAlert,
            iconTint = DangerRed,
            content = "Standard compression tools (ZIP, RAR, 7z) require you to have enough free disk space to store the entire output archive simultaneously alongside your uncompressed 50 GB files.\n\nIf your phone has only 2 GB or 5 GB of free space, standard tools immediately fail with 'Not enough disk space' or 'Disk Full'."
        )

        // The Solution
        GuideCard(
            title = "The Solution: Incremental Chunk Compression",
            icon = Icons.Default.Layers,
            iconTint = AccentCyan,
            content = "ChunkCompress solves this by breaking down the operation into manageable chunks (e.g. 1 GB, 2 GB, or 5 GB):\n\n" +
                    "1. Step 1: Pack 1 GB of files into chunk_001.zip.\n" +
                    "2. Step 2 (Verification): Open chunk_001.zip and compute SHA-256 for each entry. Compare with the original files.\n" +
                    "3. Step 3 (Safe Reclaim): Only after 100% verification passes, delete those original files from storage. You have now regained 1 GB of free space!\n" +
                    "4. Step 4: Repeat for chunk_002.zip, chunk_003.zip, etc., until the entire 50 GB folder is compressed!"
        )

        // Accuracy Guarantee
        GuideCard(
            title = "100% Accuracy & Safety Guarantee",
            icon = Icons.Default.VerifiedUser,
            iconTint = SuccessGreen,
            content = "• SHA-256 Checksums: Every single file is hashed and verified before anything is deleted.\n" +
                    "• Zero Data Loss: If verification detects even a 1-byte mismatch or error, deletion is aborted immediately.\n" +
                    "• Complete Manifest: A chunk_manifest.json catalog is generated with file attributes, timestamps, and relative paths so your original directory tree can be restored 100% faithfully.\n" +
                    "• Large File Splitting: If a single file in the 50 GB folder is larger than your chunk size, it is split into sequenced parts and reassembled upon restoration."
        )

        // Low-space restoration
        GuideCard(
            title = "Decompression on Low Space",
            icon = Icons.Default.Restore,
            iconTint = PrimaryBlue,
            content = "When you need your files back, you can also decompress piece-by-piece! In 'Low-Space Restore Mode', ChunkCompress extracts chunk_001.zip, verifies it, deletes the chunk_001.zip archive to free up space, and proceeds to chunk_002.zip."
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun GuideCard(
    title: String,
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    content: String
) {
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
                Icon(icon, contentDescription = null, tint = iconTint)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                lineHeight = 22.sp
            )
        }
    }
}
