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

        return targetFile
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
