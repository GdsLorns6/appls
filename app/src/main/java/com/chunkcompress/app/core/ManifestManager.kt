package com.chunkcompress.app.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

object ManifestManager {

    const val MANIFEST_FILE_NAME = "chunk_manifest.json"
    const val MANIFEST_BACKUP_NAME = "chunk_manifest.json.bak"
    const val MANIFEST_TEMP_NAME = "chunk_manifest.json.tmp"

    private val gson: Gson by lazy {
        GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
    }

    /**
     * Atomically and safely saves the manifest to disk with durable sync and backup.
     */
    @Synchronized
    fun saveManifest(manifest: ChunkManifest, outputDir: File): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        manifest.updatedAt = System.currentTimeMillis()

        val targetFile = File(outputDir, MANIFEST_FILE_NAME)
        val tempFile = File(outputDir, MANIFEST_TEMP_NAME)
        val backupFile = File(outputDir, MANIFEST_BACKUP_NAME)

        // 1. Write to temporary file with explicit filesystem sync
        FileOutputStream(tempFile).use { fos ->
            val writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
            gson.toJson(manifest, writer)
            writer.flush()
            fos.fd.sync() // Ensure physical disk write while descriptor is open
        }

        // 2. Safely create or update backup of previous manifest
        if (targetFile.exists() && targetFile.length() > 0) {
            try {
                if (backupFile.exists()) backupFile.delete()
                targetFile.copyTo(backupFile, overwrite = true)
            } catch (e: Exception) {
                // Non-fatal, proceed with replacement
            }
        }

        // 3. Atomic replacement
        if (!tempFile.renameTo(targetFile)) {
            // Fallback copy if rename across mount fails
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        try {
            generatePCReassemblyScripts(outputDir)
        } catch (e: Exception) {
            // Non-fatal helper script generation
        }

        return targetFile
    }

    /**
     * Automatically generates 1-click game extractor scripts for Windows (.bat) and Mac/Linux (.sh).
     */
    private fun generatePCReassemblyScripts(outputDir: File) {
        val batFile = File(outputDir, "extract_game_on_windows.bat")
        if (!batFile.exists()) {
            val batContent = "@echo off\r\n" +
                    "setlocal enabledelayedexpansion\r\n" +
                    "title ChunkCompress - Automatic Game Reassembler\r\n" +
                    "cd /d \"%~dp0\"\r\n" +
                    "echo ========================================================\r\n" +
                    "echo   ChunkCompress 2.0 - PC Game Reassembler\r\n" +
                    "echo ========================================================\r\n" +
                    "echo.\r\n" +
                    "powershell -NoProfile -ExecutionPolicy Bypass -Command ^\r\n" +
                    "  \"$scriptDir = '%~dp0'; \" ^\r\n" +
                    "  \"$outputDir = Join-Path $scriptDir 'Extracted_Game'; \" ^\r\n" +
                    "  \"Write-Host 'Target extraction folder: ' $outputDir -ForegroundColor Cyan; \" ^\r\n" +
                    "  \"New-Item -ItemType Directory -Force -Path $outputDir | Out-Null; \" ^\r\n" +
                    "  \"$chunks = Get-ChildItem -Path $scriptDir -Filter 'chunk_*.zip' | Sort-Object Name; \" ^\r\n" +
                    "  \"if ($chunks.Count -eq 0) { \" ^\r\n" +
                    "  \"  $singleZips = Get-ChildItem -Path $scriptDir -Filter '*.zip'; \" ^\r\n" +
                    "  \"  foreach ($sz in $singleZips) { Write-Host ('Extracting ' + $sz.Name + '...'); Expand-Archive -LiteralPath $sz.FullName -DestinationPath $outputDir -Force }; \" ^\r\n" +
                    "  \"} else { \" ^\r\n" +
                    "  \"  foreach ($c in $chunks) { Write-Host ('Extracting ' + $c.Name + '...') -ForegroundColor Yellow; Expand-Archive -LiteralPath $c.FullName -DestinationPath $outputDir -Force }; \" ^\r\n" +
                    "  \"}; \" ^\r\n" +
                    "  \"$splitParts = Get-ChildItem -Path $outputDir -Recurse -Filter '*.chunkpart_*'; \" ^\r\n" +
                    "  \"if ($splitParts.Count -gt 0) { \" ^\r\n" +
                    "  \"  Write-Host 'Found multi-part files (e.g. .pak files). Reassembling...' -ForegroundColor Cyan; \" ^\r\n" +
                    "  \"  $groups = $splitParts | Group-Object { $_.FullName -replace '\\.chunkpart_\\d+$', '' }; \" ^\r\n" +
                    "  \"  foreach ($g in $groups) { \" ^\r\n" +
                    "  \"    $finalFile = $g.Name; \" ^\r\n" +
                    "  \"    Write-Host ('Stitching parts into: ' + (Split-Path $finalFile -Leaf) + '...') -ForegroundColor Green; \" ^\r\n" +
                    "  \"    $orderedParts = $g.Group | Sort-Object Name; \" ^\r\n" +
                    "  \"    $outStream = [System.IO.File]::Create($finalFile); \" ^\r\n" +
                    "  \"    foreach ($p in $orderedParts) { \" ^\r\n" +
                    "  \"      $inStream = [System.IO.File]::OpenRead($p.FullName); \" ^\r\n" +
                    "  \"      $inStream.CopyTo($outStream); \" ^\r\n" +
                    "  \"      $inStream.Close(); \" ^\r\n" +
                    "  \"      Remove-Item $p.FullName -Force; \" ^\r\n" +
                    "  \"    }; \" ^\r\n" +
                    "  \"    $outStream.Close(); \" ^\r\n" +
                    "  \"  }; \" ^\r\n" +
                    "  \"}; \" ^\r\n" +
                    "  \"Write-Host ''; \" ^\r\n" +
                    "  \"Write-Host '========================================================' -ForegroundColor Green; \" ^\r\n" +
                    "  \"Write-Host ' SUCCESS! Complete game restored and ready to play!' -ForegroundColor Green; \" ^\r\n" +
                    "  \"Write-Host (' Location: ' + $outputDir) -ForegroundColor Yellow; \" ^\r\n" +
                    "  \"Write-Host '========================================================' -ForegroundColor Green; \"\r\n" +
                    "echo.\r\n" +
                    "pause\r\n"
            batFile.writeText(batContent)
        }

        val shFile = File(outputDir, "extract_game_on_mac_linux.sh")
        if (!shFile.exists()) {
            val shContent = "#!/usr/bin/env bash\n" +
                    "set -e\n" +
                    "DIR=\"\$(cd \"\$(dirname \"\${BASH_SOURCE[0]}\")\" && pwd)\"\n" +
                    "OUTPUT_DIR=\"\$DIR/Extracted_Game\"\n" +
                    "echo \"========================================================\"\n" +
                    "echo \"   ChunkCompress 2.0 - PC Game Reassembler (Mac/Linux)\"\n" +
                    "echo \"========================================================\"\n" +
                    "mkdir -p \"\$OUTPUT_DIR\"\n" +
                    "for chunk in \"\$DIR\"/*.zip; do\n" +
                    "    [ -f \"\$chunk\" ] || continue\n" +
                    "    echo \"Extracting \$(basename \"\$chunk\")...\"\n" +
                    "    unzip -q -o \"\$chunk\" -d \"\$OUTPUT_DIR\"\n" +
                    "done\n" +
                    "find \"\$OUTPUT_DIR\" -type f -name \"*.chunkpart_001\" | while read -r first_part; do\n" +
                    "    final_file=\"\${first_part%.chunkpart_001}\"\n" +
                    "    base_name=\"\$(basename \"\$final_file\")\"\n" +
                    "    echo \"Stitching parts for \$base_name...\"\n" +
                    "    dir_name=\"\$(dirname \"\$first_part\")\"\n" +
                    "    cat \"\$dir_name/\$base_name.chunkpart_\"* > \"\$final_file\"\n" +
                    "    rm -f \"\$dir_name/\$base_name.chunkpart_\"*\n" +
                    "done\n" +
                    "echo \"SUCCESS! Game restored in: \$OUTPUT_DIR\"\n"
            shFile.writeText(shContent)
            shFile.setExecutable(true, false)
        }
    }

    /**
     * Loads the manifest from the specified directory or file, with fallback to backup.
     */
    @Synchronized
    fun loadManifest(manifestFileOrDir: File): ChunkManifest? {
        val targetFile = if (manifestFileOrDir.isDirectory) {
            File(manifestFileOrDir, MANIFEST_FILE_NAME)
        } else {
            manifestFileOrDir
        }

        val backupFile = File(targetFile.parentFile ?: File("."), MANIFEST_BACKUP_NAME)

        // Try primary file
        val primaryManifest = parseAndMigrate(targetFile)
        if (primaryManifest != null) return primaryManifest

        // Try backup file if primary failed or corrupted
        if (backupFile.exists()) {
            val backupManifest = parseAndMigrate(backupFile)
            if (backupManifest != null) {
                // Restore backup to primary
                try {
                    backupFile.copyTo(targetFile, overwrite = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return backupManifest
            }
        }

        return null
    }

    /**
     * Validates and parses manifest with backward compatibility for v1 manifests.
     */
    private fun parseAndMigrate(file: File): ChunkManifest? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null

        return try {
            FileReader(file, StandardCharsets.UTF_8).use { reader ->
                val jsonElement = JsonParser.parseReader(reader)
                if (!jsonElement.isJsonObject) return null
                val jsonObject = jsonElement.asJsonObject

                val version = if (jsonObject.has("version")) jsonObject.get("version").asInt else 1

                val manifest = gson.fromJson(jsonObject, ChunkManifest::class.java)

                // Backward compatibility migration from v1
                if (version < 2) {
                    migrateFromV1(manifest)
                }

                if (isValidManifest(manifest)) {
                    manifest
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun migrateFromV1(manifest: ChunkManifest) {
        // Ensure per-file status is set for v1 files
        for (chunk in manifest.chunks) {
            for (file in chunk.files) {
                if (file.status == FileProcessStatus.PENDING) {
                    file.status = if (chunk.isVerified) FileProcessStatus.VERIFIED else FileProcessStatus.ARCHIVED
                }
            }
        }
    }

    private fun isValidManifest(manifest: ChunkManifest?): Boolean {
        if (manifest == null) return false
        if (manifest.sourceFolderName.isBlank()) return false
        if (manifest.targetChunkBytes <= 0) return false
        return true
    }
}
