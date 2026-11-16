package com.chunkcompress.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

class DecompressionEngine(
    private val manifest: ChunkManifest,
    private val chunksDir: File,
    private val destinationDir: File,
    private val config: DecompressionConfig = DecompressionConfig(),
    private val onProgress: (ProgressSnapshot) -> Unit
) {
    @Volatile
    private var isCancelled = false
    @Volatile
    private var isPaused = false

    private val logs = mutableListOf<LogMessage>()
    private var totalBytesExtracted = 0L
    private var startTime = 0L

    fun cancel() {
        isCancelled = true
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    private fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        val log = LogMessage(message = message, level = level)
        synchronized(logs) {
            logs.add(log)
            if (logs.size > 400) {
                logs.removeAt(0)
            }
        }
        emitProgress(currentLogs = logs.toList())
    }

    private fun emitProgress(
        state: EngineState = EngineState.Idle,
        currentChunkIndex: Int = 0,
        totalChunks: Int = 0,
        currentFileName: String = "",
        chunkProgress: Float = 0f,
        totalProgress: Float = 0f,
        speed: Long = 0L,
        currentLogs: List<LogMessage>? = null
    ) {
        val now = System.currentTimeMillis()
        val elapsed = if (startTime > 0) now - startTime else 0L
        val estimatedRemainingMs = if (speed > 0 && manifest.totalOriginalBytes > totalBytesExtracted) {
            ((manifest.totalOriginalBytes - totalBytesExtracted) * 1000L) / speed
        } else {
            0L
        }

        val snapshot = ProgressSnapshot(
            state = state,
            currentChunkIndex = currentChunkIndex,
            totalChunks = totalChunks,
            currentFileName = currentFileName,
            chunkProgressPercent = chunkProgress.coerceIn(0f, 1f),
            totalProgressPercent = totalProgress.coerceIn(0f, 1f),
            totalBytesProcessed = totalBytesExtracted,
            totalCompressedBytesWritten = 0L,
            totalOriginalBytes = manifest.totalOriginalBytes,
            bytesReclaimed = 0L,
            currentSpeedBytesPerSec = speed,
            elapsedMs = elapsed,
            estimatedRemainingMs = estimatedRemainingMs,
            logs = currentLogs ?: synchronized(logs) { logs.toList() }
        )
        onProgress(snapshot)
    }

    suspend fun execute() = withContext(Dispatchers.IO) {
        startTime = System.currentTimeMillis()
        addLog("=== ChunkCompress 2.0 Decompression Started ===")
        addLog("Destination: ${destinationDir.absolutePath}")
        addLog("Verify-Only Mode: ${if (config.verifyOnly) "ENABLED (No files will be written)" else "DISABLED"}")
        addLog("Conflict Strategy: ${config.conflictStrategy.name}")
        addLog("Low-Storage Restoration: ${if (config.deleteChunkAfterExtract) "ENABLED (Reclaiming archive space)" else "DISABLED"}")

        if (!config.verifyOnly && !destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        val destCanonicalPath = destinationDir.canonicalPath
        val chunksToProcess = manifest.chunks.filter { chunk ->
            config.selectedChunkIndexes == null || config.selectedChunkIndexes.contains(chunk.chunkIndex)
        }

        val totalChunks = chunksToProcess.size
        var speedStartTime = System.currentTimeMillis()
        var bytesSinceSpeedCheck = 0L
        var currentSpeed = 0L

        // Split-file tracking: maps relativePath to number of completed parts
        val splitFilesCompletedParts = mutableMapOf<String, Int>()

        for ((index, chunkInfo) in chunksToProcess.withIndex()) {
            checkActive()

            val chunkNumber = index + 1
            val archiveFile = File(chunksDir, chunkInfo.archiveFileName)

            if (!archiveFile.exists()) {
                throw IllegalStateException("Missing required chunk archive: ${archiveFile.name} in ${chunksDir.absolutePath}")
            }

            // Verify whole-archive checksum if present in manifest
            if (chunkInfo.archiveSha256 != null) {
                addLog("Verifying package checksum for ${archiveFile.name}...")
                val actualArchiveHash = HashUtils.sha256(archiveFile)
                if (!actualArchiveHash.equals(chunkInfo.archiveSha256, ignoreCase = true)) {
                    throw IllegalStateException("ARCHIVE CORRUPTED: ${archiveFile.name} SHA-256 mismatch!\nExpected: ${chunkInfo.archiveSha256}\nActual: $actualArchiveHash")
                }
            }

            addLog(">>> Processing Chunk $chunkNumber of $totalChunks: ${archiveFile.name}")
            var chunkBytesProcessed = 0L

            ZipFile(archiveFile).use { zip ->
                for (fileEntry in chunkInfo.files) {
                    checkActive()

                    while (isPaused && !isCancelled) {
                        Thread.sleep(200)
                    }

                    // Check if file is selected (for selective extraction)
                    if (config.selectedFiles != null && !config.selectedFiles.contains(fileEntry.relativePath)) {
                        continue
                    }

                    val rawRelPath = fileEntry.relativePath

                    // SECURITY: Path Traversal (Zip Slip) Protection
                    val targetFile = File(destinationDir, rawRelPath)
                    val canonicalTarget = targetFile.canonicalPath
                    if (!canonicalTarget.startsWith(destCanonicalPath + File.separator) && canonicalTarget != destCanonicalPath) {
                        throw SecurityException("MALICIOUS ZIP SLIP PATH DETECTED! Entry '$rawRelPath' attempts to escape destination directory!")
                    }

                    emitProgress(
                        state = EngineState.Extracting(
                            chunkIndex = chunkNumber,
                            totalChunks = totalChunks,
                            currentFileName = rawRelPath,
                            filesExtracted = (totalBytesExtracted / Math.max(1L, fileEntry.originalSize)).toInt(),
                            totalFiles = manifest.totalFiles
                        ),
                        currentChunkIndex = chunkNumber,
                        totalChunks = totalChunks,
                        currentFileName = rawRelPath,
                        chunkProgress = if (chunkInfo.totalUncompressedBytes > 0) chunkBytesProcessed.toFloat() / chunkInfo.totalUncompressedBytes else 0f,
                        totalProgress = if (manifest.totalOriginalBytes > 0) totalBytesExtracted.toFloat() / manifest.totalOriginalBytes else 0f,
                        speed = currentSpeed
                    )

                    if (!fileEntry.isSplit) {
                        // Standard file extraction
                        val zipEntry = zip.getEntry(rawRelPath)
                            ?: throw IllegalStateException("Entry not found in archive: $rawRelPath")

                        val digest = MessageDigest.getInstance("SHA-256")
                        var entryExtractedBytes = 0L

                        if (config.verifyOnly) {
                            // In-memory verification without writing to disk
                            zip.getInputStream(zipEntry).use { input ->
                                val buffer = ByteArray(config.bufferSizeBytes)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    checkActive()
                                    digest.update(buffer, 0, read)
                                    chunkBytesProcessed += read
                                    totalBytesExtracted += read
                                    bytesSinceSpeedCheck += read
                                    entryExtractedBytes += read
                                }
                            }
                        } else {
                            // Safe transactional extraction via .tmp file
                            val resolvedTargetFile = resolveConflict(targetFile, config.conflictStrategy)
                            if (resolvedTargetFile == null) {
                                addLog("Skipping existing file (Conflict Strategy: SKIP): $rawRelPath", LogLevel.INFO)
                                chunkBytesProcessed += fileEntry.originalSize
                                totalBytesExtracted += fileEntry.originalSize
                                continue
                            }

                            resolvedTargetFile.parentFile?.mkdirs()
                            val tempFile = File(resolvedTargetFile.parentFile, "${resolvedTargetFile.name}.tmp_${System.currentTimeMillis()}")

                            zip.getInputStream(zipEntry).use { input ->
                                BufferedOutputStream(FileOutputStream(tempFile), config.bufferSizeBytes).use { output ->
                                    val buffer = ByteArray(config.bufferSizeBytes)
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        checkActive()
                                        output.write(buffer, 0, read)
                                        digest.update(buffer, 0, read)
                                        chunkBytesProcessed += read
                                        totalBytesExtracted += read
                                        bytesSinceSpeedCheck += read
                                        entryExtractedBytes += read

                                        val now = System.currentTimeMillis()
                                        if (now - speedStartTime >= 1000) {
                                            currentSpeed = (bytesSinceSpeedCheck * 1000) / (now - speedStartTime)
                                            speedStartTime = now
                                            bytesSinceSpeedCheck = 0L
                                        }
                                    }
                                }
                            }

                            // Verify extracted byte count
                            if (entryExtractedBytes != fileEntry.originalSize) {
                                tempFile.delete()
                                throw IllegalStateException("Extracted size mismatch for $rawRelPath! Expected ${fileEntry.originalSize}, got $entryExtractedBytes")
                            }

                            // Verify SHA-256 before atomic commit
                            val actualHash = HashUtils.bytesToHex(digest.digest())
                            if (!actualHash.equals(fileEntry.sha256, ignoreCase = true)) {
                                tempFile.delete()
                                throw IllegalStateException("DECOMPRESSION CORRUPTION! SHA-256 mismatch for $rawRelPath\nExpected: ${fileEntry.sha256}\nActual: $actualHash")
                            }

                            // Commit atomically
                            if (resolvedTargetFile.exists()) resolvedTargetFile.delete()
                            if (!tempFile.renameTo(resolvedTargetFile)) {
                                tempFile.copyTo(resolvedTargetFile, overwrite = true)
                                tempFile.delete()
                            }
                            resolvedTargetFile.setLastModified(fileEntry.lastModified)
                        }
                    } else {
                        // Multi-part split file extraction & sequential reassembly
                        val splitPartName = fileEntry.splitPartFileName
                            ?: "${rawRelPath}.chunkpart_${String.format("%03d", fileEntry.partIndex)}"

                        val zipEntry = zip.getEntry(splitPartName)
                            ?: throw IllegalStateException("Split part entry not found in archive: $splitPartName")

                        val digest = MessageDigest.getInstance("SHA-256")

                        if (config.verifyOnly) {
                            zip.getInputStream(zipEntry).use { input ->
                                val buffer = ByteArray(config.bufferSizeBytes)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    checkActive()
                                    digest.update(buffer, 0, read)
                                    chunkBytesProcessed += read
                                    totalBytesExtracted += read
                                    bytesSinceSpeedCheck += read
                                }
                            }

                            val partHash = HashUtils.bytesToHex(digest.digest())
                            if (!partHash.equals(fileEntry.sha256, ignoreCase = true)) {
                                throw IllegalStateException("CORRUPTION DETECTED: Split part checksum mismatch for $splitPartName!\nExpected: ${fileEntry.sha256}\nActual: $partHash")
                            }
                        } else {
                            targetFile.parentFile?.mkdirs()
                            val stagingFile = File(targetFile.parentFile, "${targetFile.name}.staging_split")

                            try {
                                zip.getInputStream(zipEntry).use { input ->
                                    RandomAccessFile(stagingFile, "rw").use { raf ->
                                        val partOffset = (fileEntry.partIndex - 1) * manifest.targetChunkBytes
                                        raf.seek(partOffset)

                                        val buffer = ByteArray(config.bufferSizeBytes)
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            checkActive()
                                            raf.write(buffer, 0, read)
                                            digest.update(buffer, 0, read)
                                            chunkBytesProcessed += read
                                            totalBytesExtracted += read
                                            bytesSinceSpeedCheck += read

                                            val now = System.currentTimeMillis()
                                            if (now - speedStartTime >= 1000) {
                                                currentSpeed = (bytesSinceSpeedCheck * 1000) / (now - speedStartTime)
                                                speedStartTime = now
                                                bytesSinceSpeedCheck = 0L
                                            }
                                        }
                                    }
                                }

                                val partHash = HashUtils.bytesToHex(digest.digest())
                                if (!partHash.equals(fileEntry.sha256, ignoreCase = true)) {
                                    stagingFile.delete()
                                    throw IllegalStateException("Split part checksum mismatch for $splitPartName!\nExpected: ${fileEntry.sha256}\nActual: $partHash")
                                }

                                val completed = splitFilesCompletedParts.getOrDefault(rawRelPath, 0) + 1
                                splitFilesCompletedParts[rawRelPath] = completed

                                // When all parts have been received, verify whole file and commit atomically
                                if (completed >= fileEntry.totalParts) {
                                    if (stagingFile.length() != fileEntry.originalSize) {
                                        stagingFile.delete()
                                        throw IllegalStateException("Reconstructed split file size mismatch for $rawRelPath! Expected ${fileEntry.originalSize}, got ${stagingFile.length()}")
                                    }

                                    val resolvedTarget = resolveConflict(targetFile, config.conflictStrategy)
                                    if (resolvedTarget == null) {
                                        // Conflict strategy is SKIP
                                        stagingFile.delete()
                                        addLog("Skipping existing file (Conflict Strategy: SKIP): $rawRelPath", LogLevel.INFO)
                                    } else {
                                        if (resolvedTarget.exists()) resolvedTarget.delete()
                                        if (!stagingFile.renameTo(resolvedTarget)) {
                                            stagingFile.copyTo(resolvedTarget, overwrite = true)
                                            stagingFile.delete()
                                        }
                                        resolvedTarget.setLastModified(fileEntry.lastModified)
                                        addLog("Fully reconstructed split file: $rawRelPath (${StorageUtils.formatBytes(resolvedTarget.length())})", LogLevel.SUCCESS)
                                    }
                                }
                            } catch (e: Exception) {
                                if (stagingFile.exists()) stagingFile.delete()
                                throw e
                            }
                        }
                    }
                }
            }

            addLog("Chunk $chunkNumber verified and extracted successfully.", LogLevel.SUCCESS)

            // Low-storage restore mode: safely delete chunk archive to free up space
            if (config.deleteChunkAfterExtract && !config.verifyOnly) {
                val sizeFreed = chunkInfo.archiveSizeBytes
                if (archiveFile.delete()) {
                    addLog("Deleted archive ${archiveFile.name} (Freed ${StorageUtils.formatBytes(sizeFreed)} disk space)", LogLevel.INFO)
                }
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        addLog(
            if (config.verifyOnly) "Archive integrity verification completed in ${totalTime / 1000}s! All entries 100% valid."
            else "Decompression completed successfully in ${totalTime / 1000}s! Restored to: ${destinationDir.absolutePath}",
            LogLevel.SUCCESS
        )

        emitProgress(
            state = EngineState.Completed(
                totalChunks = totalChunks,
                totalOriginalBytes = manifest.totalOriginalBytes,
                totalReclaimedBytes = 0L,
                timeTakenMs = totalTime
            ),
            currentChunkIndex = totalChunks,
            totalChunks = totalChunks,
            totalProgress = 1f,
            chunkProgress = 1f
        )
    }

    private fun checkActive() {
        if (isCancelled) {
            throw CancellationException("Operation cancelled by user.")
        }
    }

    private fun resolveConflict(targetFile: File, strategy: ConflictStrategy): File? {
        if (!targetFile.exists()) return targetFile

        return when (strategy) {
            ConflictStrategy.OVERWRITE -> targetFile
            ConflictStrategy.SKIP -> null
            ConflictStrategy.RENAME -> {
                val parent = targetFile.parentFile
                val baseName = targetFile.nameWithoutExtension
                val ext = targetFile.extension
                var counter = 1
                var candidate: File
                do {
                    val newName = if (ext.isNotEmpty()) "$baseName ($counter).$ext" else "$baseName ($counter)"
                    candidate = File(parent, newName)
                    counter++
                } while (candidate.exists())
                candidate
            }
        }
    }
}
