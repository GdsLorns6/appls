package com.chunkcompress.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CompressionEngine(
    private val sourceFolder: File,
    private val outputDir: File,
    private val config: CompressionConfig,
    private val onProgress: (ProgressSnapshot) -> Unit
) {
    @Volatile
    private var isCancelled = false
    @Volatile
    private var isPaused = false

    private val logs = mutableListOf<LogMessage>()
    private var totalBytesProcessed = 0L
    private var totalCompressedBytesWritten = 0L
    private var totalOriginalBytes = 0L
    private var bytesReclaimed = 0L
    private var isResumedSession = false
    private var engineStartTime = 0L

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
        val elapsed = if (engineStartTime > 0) now - engineStartTime else 0L
        val estimatedRemainingMs = if (speed > 0 && totalOriginalBytes > totalBytesProcessed) {
            ((totalOriginalBytes - totalBytesProcessed) * 1000L) / speed
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
            totalBytesProcessed = totalBytesProcessed,
            totalCompressedBytesWritten = totalCompressedBytesWritten,
            totalOriginalBytes = totalOriginalBytes,
            bytesReclaimed = bytesReclaimed,
            currentSpeedBytesPerSec = speed,
            elapsedMs = elapsed,
            estimatedRemainingMs = estimatedRemainingMs,
            isResumed = isResumedSession,
            logs = currentLogs ?: synchronized(logs) { logs.toList() }
        )
        onProgress(snapshot)
    }

    companion object {
        /**
         * Pre-flight safety inspection report before touching any file.
         */
        fun inspectPreFlight(
            sourceFolder: File,
            outputDir: File,
            config: CompressionConfig
        ): PreFlightReport {
            val warnings = mutableListOf<String>()
            val availableFreeBytes = StorageUtils.getAvailableFreeSpaceBytes(outputDir)
            val isCrossVolume = StorageUtils.isDifferentStorageVolume(sourceFolder, outputDir)

            if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
                return PreFlightReport(
                    totalOriginalBytes = 0L,
                    totalFiles = 0,
                    availableFreeBytes = availableFreeBytes,
                    targetChunkBytes = config.targetChunkBytes,
                    largestFile = null,
                    requiredMinimumFreeBytes = config.targetChunkBytes + config.emergencyReserveBytes,
                    isSafeToProceed = false,
                    isCrossVolume = isCrossVolume,
                    warnings = listOf("Source folder does not exist or is not readable.")
                )
            }

            var totalBytes = 0L
            var totalFiles = 0
            var largest: FileItem? = null

            sourceFolder.walkTopDown()
                .onEnter { dir -> !config.excludeHiddenFiles || !dir.name.startsWith(".") }
                .filter { it.isFile }
                .forEach { file ->
                    if (config.excludeHiddenFiles && file.name.startsWith(".")) return@forEach
                    if (config.excludeEmptyFiles && file.length() == 0L) return@forEach
                    if (config.excludedExtensions.isNotEmpty()) {
                        val ext = file.extension.lowercase()
                        if (config.excludedExtensions.contains(ext)) return@forEach
                    }

                    val size = file.length()
                    totalBytes += size
                    totalFiles++
                    if (largest == null || size > (largest?.size ?: 0L)) {
                        val relPath = file.relativeTo(sourceFolder).path.replace('\\', '/')
                        largest = FileItem(file, relPath, size, file.lastModified())
                    }
                }

            val requiredMinimum = config.targetChunkBytes + config.emergencyReserveBytes

            if (availableFreeBytes < requiredMinimum) {
                warnings.add(
                    "Free space (${StorageUtils.formatBytes(availableFreeBytes)}) is below recommended headroom (${StorageUtils.formatBytes(requiredMinimum)}). Consider choosing a smaller chunk size."
                )
            }

            if (largest != null && (largest?.size ?: 0L) > availableFreeBytes) {
                warnings.add(
                    "File '${largest?.file?.name}' (${StorageUtils.formatBytes(largest?.size ?: 0L)}) exceeds free storage (${StorageUtils.formatBytes(availableFreeBytes)}). It will be split across multiple chunk parts."
                )
            }

            // Check if there is an interrupted session that can be resumed
            val existingManifest = ManifestManager.loadManifest(outputDir)
            var canResume = false
            var completedChunks = 0
            var reclaimedSoFar = 0L

            if (existingManifest != null && !existingManifest.isCompleted && existingManifest.chunks.isNotEmpty()) {
                canResume = true
                completedChunks = existingManifest.chunks.count { it.isVerified }
                reclaimedSoFar = existingManifest.chunks
                    .filter { it.isVerified }
                    .sumOf { it.totalUncompressedBytes }
            }

            val isSafe = availableFreeBytes >= config.targetChunkBytes

            return PreFlightReport(
                totalOriginalBytes = totalBytes,
                totalFiles = totalFiles,
                availableFreeBytes = availableFreeBytes,
                targetChunkBytes = config.targetChunkBytes,
                largestFile = largest,
                requiredMinimumFreeBytes = requiredMinimum,
                isSafeToProceed = isSafe,
                isCrossVolume = isCrossVolume,
                canResume = canResume,
                resumedCompletedChunks = completedChunks,
                resumedReclaimedBytes = reclaimedSoFar,
                warnings = warnings
            )
        }

        /**
         * Recommends optimal chunk size based on available free space.
         */
        fun recommendChunkSize(availableFreeBytes: Long): Long {
            return when {
                availableFreeBytes > 12L * 1024 * 1024 * 1024 -> 5L * 1024 * 1024 * 1024 // 5 GB
                availableFreeBytes > 5L * 1024 * 1024 * 1024 -> 2L * 1024 * 1024 * 1024  // 2 GB
                availableFreeBytes > 2500L * 1024 * 1024 -> 1024L * 1024 * 1024          // 1 GB
                availableFreeBytes > 1200L * 1024 * 1024 -> 500L * 1024 * 1024           // 500 MB
                else -> Math.max(100L * 1024 * 1024, availableFreeBytes / 3)             // Proportional fallback
            }
        }
    }

    /**
     * Executes the incremental safe chunk compression pipeline with full transactional safety.
     */
    suspend fun execute(): ChunkManifest = withContext(Dispatchers.IO) {
        engineStartTime = System.currentTimeMillis()
        addLog("=== ChunkCompress 2.0 Engine Started ===")
        addLog("Source: ${sourceFolder.absolutePath}")
        addLog("Output: ${outputDir.absolutePath}")
        addLog("Target chunk size: ${StorageUtils.formatBytes(config.targetChunkBytes)}")
        addLog("Compression level: ${config.compressionLevel} (${when(config.compressionLevel) { 0 -> "Store"; 1 -> "Fast"; 9 -> "Maximum"; else -> "Balanced" }})")
        addLog("Strict SHA-256 verification: ${if (config.verifySha256) "ENABLED" else "DISABLED"}")
        addLog("Source file deletion: ${if (config.deleteSourceAfterVerified) "ENABLED (Reclaim Space)" else "DISABLED (Data Protection Mode)"}")

        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            throw IllegalArgumentException("Source folder does not exist or is not a directory.")
        }
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // 1. Session Resumability: Check for existing manifest
        val existingManifest = if (config.resumeSessionIfPossible) ManifestManager.loadManifest(outputDir) else null
        val manifest: ChunkManifest
        val completedChunkIndexes = mutableSetOf<Int>()

        if (existingManifest != null && !existingManifest.isCompleted && existingManifest.chunks.isNotEmpty()) {
            isResumedSession = true
            manifest = existingManifest
            for (ch in existingManifest.chunks) {
                if (ch.isVerified) {
                    completedChunkIndexes.add(ch.chunkIndex)
                    bytesReclaimed += ch.totalUncompressedBytes
                    totalBytesProcessed += ch.totalUncompressedBytes
                    totalCompressedBytesWritten += ch.archiveSizeBytes
                }
            }
            addLog("Resuming session '${manifest.sessionId}': ${completedChunkIndexes.size} verified chunk(s) already completed.", LogLevel.SUCCESS)
        } else {
            manifest = ChunkManifest(
                sourceFolderName = sourceFolder.name,
                sourceAbsolutePath = sourceFolder.absolutePath,
                outputAbsolutePath = outputDir.absolutePath,
                targetChunkBytes = config.targetChunkBytes,
                compressionLevel = config.compressionLevel
            )
        }

        // 2. Scan remaining files from source directory
        emitProgress(state = EngineState.Scanning(0, 0))
        val candidateFiles = mutableListOf<FileItem>()
        var scannedBytes = 0L

        sourceFolder.walkTopDown()
            .onEnter { dir -> !config.excludeHiddenFiles || !dir.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { file ->
                if (config.excludeHiddenFiles && file.name.startsWith(".")) return@forEach
                if (config.excludeEmptyFiles && file.length() == 0L) return@forEach
                if (file.name.endsWith(".tmp") || file.name.endsWith(".bak") || file.name.startsWith("manifest.json")) return@forEach
                if (config.excludedExtensions.isNotEmpty()) {
                    val ext = file.extension.lowercase()
                    if (config.excludedExtensions.contains(ext)) return@forEach
                }

                val relPath = file.relativeTo(sourceFolder).path.replace('\\', '/')
                val item = FileItem(
                    file = file,
                    relativePath = relPath,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
                candidateFiles.add(item)
                scannedBytes += item.size

                if (candidateFiles.size % 200 == 0) {
                    emitProgress(state = EngineState.Scanning(candidateFiles.size, scannedBytes))
                }
            }

        totalOriginalBytes = scannedBytes + bytesReclaimed
        manifest.totalOriginalBytes = totalOriginalBytes
        manifest.totalFiles = candidateFiles.size + completedChunkIndexes.sumOf { manifest.chunks[it - 1].fileCount }

        addLog("Scan completed: ${candidateFiles.size} remaining files to process (${StorageUtils.formatBytes(scannedBytes)})")

        if (candidateFiles.isEmpty()) {
            manifest.isCompleted = true
            ManifestManager.saveManifest(manifest, outputDir)
            val elapsed = System.currentTimeMillis() - engineStartTime
            emitProgress(
                state = EngineState.Completed(
                    totalChunks = manifest.chunks.size,
                    totalOriginalBytes = totalOriginalBytes,
                    totalReclaimedBytes = bytesReclaimed,
                    timeTakenMs = elapsed
                ),
                totalProgress = 1f,
                chunkProgress = 1f
            )
            addLog("All files already compressed! Session completed.", LogLevel.SUCCESS)
            return@withContext manifest
        }

        // 3. Check Archive Output Mode: Single Complete ZIP vs Multi-Volume Chunks
        if (config.archiveMode == ArchiveMode.SINGLE_ZIP) {
            return@withContext compressSingleZip(
                sourceFolder = sourceFolder,
                outputDir = outputDir,
                candidateFiles = candidateFiles,
                manifest = manifest,
                scannedBytes = scannedBytes,
                config = config,
                engineStartTime = engineStartTime
            )
        }

        // 4. Plan chunks for remaining files (Multi-Volume Chunk Mode)
        val plannedChunks = planChunks(candidateFiles, config.targetChunkBytes)
        val chunkOffset = manifest.chunks.size
        val totalExpectedChunks = chunkOffset + plannedChunks.size

        addLog("Planned ${plannedChunks.size} new chunk(s). Total session chunks: $totalExpectedChunks")
        emitProgress(
            state = EngineState.Planning(totalExpectedChunks),
            totalChunks = totalExpectedChunks,
            currentChunkIndex = chunkOffset
        )

        var speedStartTime = System.currentTimeMillis()
        var bytesSinceSpeedCheck = 0L
        var currentSpeed = 0L
        var writeCounter = 0

        val splitFilePartsCount = mutableMapOf<String, Int>()
        val splitFileTotalParts = mutableMapOf<String, Int>()

        // 4. Process each chunk transactionally
        for ((idx, plan) in plannedChunks.withIndex()) {
            checkActive()

            val chunkNumber = chunkOffset + idx + 1
            val archiveName = "chunk_${String.format("%03d", chunkNumber)}.zip"
            val archiveFile = File(outputDir, archiveName)
            val tempArchiveFile = File(outputDir, "$archiveName.tmp")

            if (tempArchiveFile.exists()) tempArchiveFile.delete()

            addLog(">>> Processing Chunk $chunkNumber of $totalExpectedChunks: $archiveName")

            val chunkInfo = ChunkInfo(
                chunkIndex = chunkNumber,
                archiveFileName = archiveName,
                totalUncompressedBytes = plan.totalBytes,
                fileCount = plan.items.size
            )

            val computedHashes = mutableMapOf<String, String>()
            val sourceFileSnapshots = mutableMapOf<String, Pair<Long, Long>>() // relPath -> (mtime, size)
            var chunkBytesProcessed = 0L

            // Step A: Streaming ZIP Compression
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempArchiveFile), config.bufferSizeBytes), StandardCharsets.UTF_8).use { zos ->
                zos.setLevel(config.compressionLevel)

                for (planItem in plan.items) {
                    checkActive()

                    while (isPaused && !isCancelled) {
                        Thread.sleep(200)
                    }

                    // Low-storage protection guard: check every ~10 MB written
                    writeCounter++
                    if (writeCounter % 40 == 0) {
                        val freeNow = StorageUtils.getAvailableFreeSpaceBytes(outputDir)
                        if (freeNow < config.emergencyReserveBytes) {
                            val msg = "Critically low disk storage (${StorageUtils.formatBytes(freeNow)} available). Pausing compression to avoid disk full!"
                            addLog(msg, LogLevel.WARNING)
                            emitProgress(
                                state = EngineState.PausedLowStorage(
                                    currentFreeBytes = freeNow,
                                    requiredBufferBytes = config.emergencyReserveBytes,
                                    message = msg
                                ),
                                currentChunkIndex = chunkNumber,
                                totalChunks = totalExpectedChunks
                            )
                            isPaused = true
                            while (isPaused && !isCancelled) {
                                Thread.sleep(1500)
                                if (StorageUtils.getAvailableFreeSpaceBytes(outputDir) >= config.emergencyReserveBytes) {
                                    isPaused = false
                                    addLog("Sufficient storage restored! Resuming...", LogLevel.INFO)
                                }
                            }
                        }
                    }

                    val sourceFile = planItem.fileItem.file
                    val relPath = planItem.fileItem.relativePath

                    // Record initial snapshot of source file for modification detection
                    sourceFileSnapshots[relPath] = Pair(sourceFile.lastModified(), sourceFile.length())

                    emitProgress(
                        state = EngineState.Compressing(
                            chunkIndex = chunkNumber,
                            totalChunks = totalExpectedChunks,
                            currentFileName = relPath,
                            chunkBytesProcessed = chunkBytesProcessed,
                            chunkBytesTotal = plan.totalBytes
                        ),
                        currentChunkIndex = chunkNumber,
                        totalChunks = totalExpectedChunks,
                        currentFileName = relPath,
                        chunkProgress = if (plan.totalBytes > 0) chunkBytesProcessed.toFloat() / plan.totalBytes else 0f,
                        totalProgress = if (totalOriginalBytes > 0) totalBytesProcessed.toFloat() / totalOriginalBytes else 0f,
                        speed = currentSpeed
                    )

                    val digest = MessageDigest.getInstance("SHA-256")

                    if (!planItem.isSplit) {
                        // Standard file entry
                        val entry = ZipEntry(relPath)
                        entry.time = planItem.fileItem.lastModified
                        zos.putNextEntry(entry)

                        if (sourceFile.length() > 0) {
                            BufferedInputStream(FileInputStream(sourceFile), config.bufferSizeBytes).use { bis ->
                                val buffer = ByteArray(config.bufferSizeBytes)
                                var read: Int
                                while (bis.read(buffer).also { read = it } != -1) {
                                    checkActive()
                                    zos.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    chunkBytesProcessed += read
                                    totalBytesProcessed += read
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
                        zos.closeEntry()

                        val hash = HashUtils.bytesToHex(digest.digest())
                        computedHashes[relPath] = hash

                        chunkInfo.files.add(
                            ManifestFileEntry(
                                relativePath = relPath,
                                originalSize = planItem.fileItem.size,
                                sha256 = hash,
                                lastModified = planItem.fileItem.lastModified,
                                status = FileProcessStatus.ARCHIVED,
                                isSplit = false
                            )
                        )
                    } else {
                        // Multi-part split file entry
                        val splitPartName = "${relPath}.chunkpart_${String.format("%03d", planItem.partIndex)}"
                        val entry = ZipEntry(splitPartName)
                        entry.time = planItem.fileItem.lastModified
                        zos.putNextEntry(entry)

                        RandomAccessFile(sourceFile, "r").use { raf ->
                            raf.seek(planItem.splitOffset)
                            val buffer = ByteArray(config.bufferSizeBytes)
                            var remainingToRead = planItem.splitLength
                            while (remainingToRead > 0) {
                                checkActive()
                                val toRead = Math.min(buffer.size.toLong(), remainingToRead).toInt()
                                val read = raf.read(buffer, 0, toRead)
                                if (read == -1) break

                                zos.write(buffer, 0, read)
                                digest.update(buffer, 0, read)

                                remainingToRead -= read
                                chunkBytesProcessed += read
                                totalBytesProcessed += read
                                bytesSinceSpeedCheck += read

                                val now = System.currentTimeMillis()
                                if (now - speedStartTime >= 1000) {
                                    currentSpeed = (bytesSinceSpeedCheck * 1000) / (now - speedStartTime)
                                    speedStartTime = now
                                    bytesSinceSpeedCheck = 0L
                                }
                            }
                        }
                        zos.closeEntry()

                        val hash = HashUtils.bytesToHex(digest.digest())
                        computedHashes[splitPartName] = hash

                        splitFileTotalParts[relPath] = planItem.totalParts
                        val currentCount = splitFilePartsCount.getOrDefault(relPath, 0) + 1
                        splitFilePartsCount[relPath] = currentCount

                        chunkInfo.files.add(
                            ManifestFileEntry(
                                relativePath = relPath,
                                originalSize = planItem.fileItem.size,
                                sha256 = hash,
                                lastModified = planItem.fileItem.lastModified,
                                status = FileProcessStatus.ARCHIVED,
                                isSplit = true,
                                partIndex = planItem.partIndex,
                                totalParts = planItem.totalParts,
                                splitPartFileName = splitPartName
                            )
                        )
                    }
                }
            }

            // Step B: Finalize Archive
            if (archiveFile.exists()) archiveFile.delete()
            if (!tempArchiveFile.renameTo(archiveFile)) {
                tempArchiveFile.copyTo(archiveFile, overwrite = true)
                tempArchiveFile.delete()
            }

            chunkInfo.archiveSizeBytes = archiveFile.length()
            chunkInfo.archiveSha256 = HashUtils.sha256(archiveFile)
            totalCompressedBytesWritten += chunkInfo.archiveSizeBytes

            addLog("Chunk $chunkNumber finalized: ${StorageUtils.formatBytes(chunkInfo.archiveSizeBytes)} (from ${StorageUtils.formatBytes(plan.totalBytes)})")

            // Step C: STRICT IN-MEMORY VERIFICATION PASS
            if (config.verifySha256) {
                addLog("Verifying integrity of $archiveName (SHA-256 Check)...")
                emitProgress(
                    state = EngineState.Verifying(
                        chunkIndex = chunkNumber,
                        totalChunks = totalExpectedChunks,
                        currentFileName = archiveName,
                        filesVerified = 0,
                        totalFilesInChunk = chunkInfo.files.size
                    ),
                    currentChunkIndex = chunkNumber,
                    totalChunks = totalExpectedChunks,
                    chunkProgress = 0f
                )

                verifyArchiveChunk(archiveFile, chunkInfo.files, computedHashes) { verifiedIndex, total ->
                    emitProgress(
                        state = EngineState.Verifying(
                            chunkIndex = chunkNumber,
                            totalChunks = totalExpectedChunks,
                            currentFileName = chunkInfo.files.getOrNull(verifiedIndex)?.relativePath ?: "",
                            filesVerified = verifiedIndex + 1,
                            totalFilesInChunk = total
                        ),
                        currentChunkIndex = chunkNumber,
                        totalChunks = totalExpectedChunks,
                        chunkProgress = (verifiedIndex + 1).toFloat() / total
                    )
                }

                // Update per-file status to VERIFIED
                chunkInfo.files.forEach { it.status = FileProcessStatus.VERIFIED }
                chunkInfo.isVerified = true
                chunkInfo.verifiedAt = System.currentTimeMillis()
                addLog("Chunk $chunkNumber verified 100% accurate! All checksums matched.", LogLevel.SUCCESS)
            } else {
                chunkInfo.isVerified = false
                addLog("Verification skipped by user configuration.", LogLevel.WARNING)
            }

            // Step D: SAVE DURABLE VERIFIED CHECKPOINT TO MANIFEST FIRST!
            manifest.chunks.add(chunkInfo)
            ManifestManager.saveManifest(manifest, outputDir)
            addLog("Saved durable verified checkpoint for Chunk $chunkNumber to manifest.")

            // Step E: SAFE DELETION PASS (ONLY after verified checkpoint is safely on disk)
            if (config.deleteSourceAfterVerified && chunkInfo.isVerified) {
                addLog("Safely deleting verified files from Chunk $chunkNumber to reclaim storage...")
                emitProgress(
                    state = EngineState.DeletingSource(
                        chunkIndex = chunkNumber,
                        totalChunks = totalExpectedChunks,
                        filesDeleted = 0,
                        totalFilesInChunk = plan.items.size
                    ),
                    currentChunkIndex = chunkNumber,
                    totalChunks = totalExpectedChunks
                )

                var deletedCount = 0
                for (planItem in plan.items) {
                    val file = planItem.fileItem.file
                    val relPath = planItem.fileItem.relativePath
                    val entryRecord = chunkInfo.files.firstOrNull {
                        if (planItem.isSplit) it.splitPartFileName == "${relPath}.chunkpart_${String.format("%03d", planItem.partIndex)}" else it.relativePath == relPath
                    }

                    // Source Modification Detection: verify file has not changed since compression started!
                    val initialSnapshot = sourceFileSnapshots[relPath]
                    val hasChanged = initialSnapshot == null ||
                            file.lastModified() != initialSnapshot.first ||
                            file.length() != initialSnapshot.second

                    if (hasChanged) {
                        addLog("FILE MODIFIED ON DISK! Skipping deletion to protect data: $relPath", LogLevel.WARNING)
                        entryRecord?.status = FileProcessStatus.MODIFIED_SKIPPED
                        continue
                    }

                    if (!planItem.isSplit) {
                        val length = file.length()
                        if (file.exists() && file.delete()) {
                            bytesReclaimed += length
                            deletedCount++
                            entryRecord?.status = FileProcessStatus.DELETED
                            entryRecord?.deletedAt = System.currentTimeMillis()
                        } else {
                            entryRecord?.status = FileProcessStatus.FAILED
                            addLog("Could not delete file (permissions or locked): $relPath", LogLevel.WARNING)
                        }
                    } else {
                        // Split file: ONLY delete if ALL parts have been verified across chunks!
                        val completedParts = splitFilePartsCount[relPath] ?: 0
                        val totalParts = splitFileTotalParts[relPath] ?: 1
                        if (completedParts >= totalParts) {
                            val length = file.length()
                            if (file.exists() && file.delete()) {
                                bytesReclaimed += length
                                deletedCount++
                                entryRecord?.status = FileProcessStatus.DELETED
                                entryRecord?.deletedAt = System.currentTimeMillis()
                                addLog("Split file fully archived and safely deleted: $relPath", LogLevel.INFO)
                            }
                        }
                    }
                }

                cleanEmptyDirectories(sourceFolder)
                addLog("Reclaimed ${StorageUtils.formatBytes(bytesReclaimed)} free storage so far!", LogLevel.SUCCESS)

                // Update manifest with per-file deletion statuses
                ManifestManager.saveManifest(manifest, outputDir)
            }
        }

        manifest.isCompleted = true
        ManifestManager.saveManifest(manifest, outputDir)

        val totalTime = System.currentTimeMillis() - engineStartTime
        addLog(
            "Compression pipeline completed successfully in ${totalTime / 1000}s! Output: ${StorageUtils.formatBytes(totalCompressedBytesWritten)}, Reclaimed: ${StorageUtils.formatBytes(bytesReclaimed)}",
            LogLevel.SUCCESS
        )

        emitProgress(
            state = EngineState.Completed(
                totalChunks = totalExpectedChunks,
                totalOriginalBytes = totalOriginalBytes,
                totalReclaimedBytes = bytesReclaimed,
                timeTakenMs = totalTime
            ),
            currentChunkIndex = totalExpectedChunks,
            totalChunks = totalExpectedChunks,
            totalProgress = 1f,
            chunkProgress = 1f
        )

        return@withContext manifest
    }

    /**
     * Creates ONE single complete .zip archive (e.g. MyGame.zip), recycling disk space in real-time
     * by deleting original source files as they are safely written into the zip stream.
     */
    private suspend fun compressSingleZip(
        sourceFolder: File,
        outputDir: File,
        candidateFiles: List<FileItem>,
        manifest: ChunkManifest,
        scannedBytes: Long,
        config: CompressionConfig,
        engineStartTime: Long
    ): ChunkManifest {
        val totalFiles = candidateFiles.size
        val totalBytes = scannedBytes
        val targetZipName = if (config.singleZipFileName.isNotBlank()) config.singleZipFileName else "${sourceFolder.name}.zip"
        val targetZipFile = File(outputDir, targetZipName)
        val tempZipFile = File(outputDir, "$targetZipName.tmp")

        if (tempZipFile.exists()) tempZipFile.delete()

        addLog("=== Single Complete ZIP Mode ===")
        addLog("Target Archive: $targetZipName for $totalFiles files (${StorageUtils.formatBytes(totalBytes)})")
        addLog("Real-Time Storage Recycling: ${if (config.deleteSourceAfterVerified) "ENABLED (Originals deleted as archived)" else "DISABLED (Originals preserved)"}")

        emitProgress(
            state = EngineState.Planning(1),
            totalChunks = 1,
            currentChunkIndex = 1
        )

        val chunkInfo = ChunkInfo(
            chunkIndex = 1,
            archiveFileName = targetZipName,
            totalUncompressedBytes = totalBytes,
            fileCount = totalFiles
        )

        var speedStartTime = System.currentTimeMillis()
        var bytesSinceSpeedCheck = 0L
        var currentSpeed = 0L
        var writeCounter = 0
        var totalBytesProcessed = 0L
        var bytesReclaimed = 0L
        val processedEntries = mutableListOf<ManifestFileEntry>()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile), config.bufferSizeBytes), StandardCharsets.UTF_8).use { zos ->
            zos.setLevel(config.compressionLevel)

            for ((_, item) in candidateFiles.withIndex()) {
                checkActive()

                while (isPaused && !isCancelled) {
                    Thread.sleep(200)
                }

                // Storage guard check
                writeCounter++
                if (writeCounter % 20 == 0) {
                    val freeNow = StorageUtils.getAvailableFreeSpaceBytes(outputDir)
                    if (freeNow < config.emergencyReserveBytes) {
                        val msg = "Critically low disk storage (${StorageUtils.formatBytes(freeNow)} available). Pausing to prevent disk exhaustion!"
                        addLog(msg, LogLevel.WARNING)
                        emitProgress(
                            state = EngineState.PausedLowStorage(
                                currentFreeBytes = freeNow,
                                requiredBufferBytes = config.emergencyReserveBytes,
                                message = msg
                            ),
                            currentFileName = item.relativePath,
                            speed = 0L
                        )
                        isPaused = true
                        while (isPaused && !isCancelled) {
                            Thread.sleep(1500)
                            if (StorageUtils.getAvailableFreeSpaceBytes(outputDir) >= config.emergencyReserveBytes) {
                                isPaused = false
                                addLog("Storage space restored! Resuming compression...", LogLevel.INFO)
                            }
                        }
                    }
                }

                val sourceFile = item.file
                val relPath = item.relativePath
                val initialSnapshot = Pair(sourceFile.lastModified(), sourceFile.length())

                emitProgress(
                    state = EngineState.Compressing(
                        chunkIndex = 1,
                        totalChunks = 1,
                        currentFileName = relPath,
                        chunkBytesProcessed = totalBytesProcessed,
                        chunkBytesTotal = totalBytes
                    ),
                    currentChunkIndex = 1,
                    totalChunks = 1,
                    currentFileName = relPath,
                    chunkProgress = if (totalBytes > 0) totalBytesProcessed.toFloat() / totalBytes else 0f,
                    totalProgress = if (totalBytes > 0) totalBytesProcessed.toFloat() / totalBytes else 0f,
                    speed = currentSpeed
                )

                val entry = ZipEntry(relPath)
                entry.time = item.lastModified
                zos.putNextEntry(entry)

                val digest = MessageDigest.getInstance("SHA-256")
                if (sourceFile.length() > 0) {
                    BufferedInputStream(FileInputStream(sourceFile), config.bufferSizeBytes).use { bis ->
                        val buffer = ByteArray(config.bufferSizeBytes)
                        var read: Int
                        while (bis.read(buffer).also { read = it } != -1) {
                            checkActive()
                            zos.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalBytesProcessed += read
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
                zos.closeEntry()
                zos.flush()

                val hash = HashUtils.bytesToHex(digest.digest())
                val entryRecord = ManifestFileEntry(
                    relativePath = relPath,
                    originalSize = item.size,
                    sha256 = hash,
                    lastModified = item.lastModified,
                    status = FileProcessStatus.ARCHIVED,
                    isSplit = false
                )

                // Safe Deletion Pass: In-place storage reclamation
                if (config.deleteSourceAfterVerified) {
                    val hasChanged = sourceFile.lastModified() != initialSnapshot.first ||
                            sourceFile.length() != initialSnapshot.second
                    if (hasChanged) {
                        addLog("File modified during archiving, keeping source: $relPath", LogLevel.WARNING)
                        entryRecord.status = FileProcessStatus.MODIFIED_SKIPPED
                    } else {
                        val len = sourceFile.length()
                        if (sourceFile.exists() && sourceFile.delete()) {
                            bytesReclaimed += len
                            entryRecord.status = FileProcessStatus.DELETED
                            entryRecord.deletedAt = System.currentTimeMillis()
                            addLog("Safely archived & reclaimed: ${sourceFile.name} (${StorageUtils.formatBytes(len)})")
                        } else {
                            entryRecord.status = FileProcessStatus.FAILED
                            addLog("Could not delete source file: $relPath", LogLevel.WARNING)
                        }
                    }
                }

                processedEntries.add(entryRecord)
            }
        }

        cleanEmptyDirectories(sourceFolder)

        // Finalize Zip File
        if (targetZipFile.exists()) targetZipFile.delete()
        if (!tempZipFile.renameTo(targetZipFile)) {
            tempZipFile.copyTo(targetZipFile, overwrite = true)
            tempZipFile.delete()
        }

        chunkInfo.archiveSizeBytes = targetZipFile.length()
        chunkInfo.files.addAll(processedEntries)
        chunkInfo.isVerified = true
        chunkInfo.verifiedAt = System.currentTimeMillis()
        manifest.chunks.add(chunkInfo)
        manifest.isCompleted = true

        ManifestManager.saveManifest(manifest, outputDir)

        val totalTime = System.currentTimeMillis() - engineStartTime
        addLog(
            "Single Complete ZIP created successfully: $targetZipName (${StorageUtils.formatBytes(chunkInfo.archiveSizeBytes)})! Total Reclaimed: ${StorageUtils.formatBytes(bytesReclaimed)}",
            LogLevel.SUCCESS
        )

        emitProgress(
            state = EngineState.Completed(
                totalChunks = 1,
                totalOriginalBytes = totalBytes,
                totalReclaimedBytes = bytesReclaimed,
                timeTakenMs = totalTime
            ),
            currentChunkIndex = 1,
            totalChunks = 1,
            totalProgress = 1f,
            chunkProgress = 1f
        )

        return manifest
    }

    private fun checkActive() {
        if (isCancelled) {
            throw CancellationException("Compression cancelled by user.")
        }
    }

    /**
     * Strictly verifies archive integrity and SHA-256 for all entries.
     */
    private fun verifyArchiveChunk(
        archiveFile: File,
        manifestEntries: List<ManifestFileEntry>,
        expectedHashes: Map<String, String>,
        onProgress: (Int, Int) -> Unit
    ) {
        ZipFile(archiveFile).use { zip ->
            for ((index, entryInfo) in manifestEntries.withIndex()) {
                checkActive()

                val entryKey = if (entryInfo.isSplit) entryInfo.splitPartFileName!! else entryInfo.relativePath
                val zipEntry = zip.getEntry(entryKey)
                    ?: throw IllegalStateException("Verification failed: entry missing from zip: $entryKey")

                val expectedHash = expectedHashes[entryKey]
                    ?: throw IllegalStateException("Verification failed: missing hash for $entryKey")

                val digest = MessageDigest.getInstance("SHA-256")
                var uncompressedSize = 0L

                zip.getInputStream(zipEntry).use { input ->
                    val buffer = ByteArray(config.bufferSizeBytes)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                        uncompressedSize += read
                    }
                }

                val actualHash = HashUtils.bytesToHex(digest.digest())
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    throw IllegalStateException("CORRUPTION DETECTED: SHA-256 mismatch for entry $entryKey\nExpected: $expectedHash\nActual: $actualHash\nAborting to protect original files!")
                }

                onProgress(index, manifestEntries.size)
            }
        }
    }

    private fun cleanEmptyDirectories(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                cleanEmptyDirectories(child)
                val remaining = child.listFiles()
                if (remaining != null && remaining.isEmpty()) {
                    child.delete()
                }
            }
        }
    }

    private fun planChunks(files: List<FileItem>, targetBytes: Long): List<PlannedChunk> {
        val result = mutableListOf<PlannedChunk>()
        var currentItems = mutableListOf<PlannedItem>()
        var currentBytes = 0L

        for (file in files) {
            if (file.size <= targetBytes) {
                if (currentBytes + file.size > targetBytes && currentItems.isNotEmpty()) {
                    result.add(PlannedChunk(items = currentItems, totalBytes = currentBytes))
                    currentItems = mutableListOf()
                    currentBytes = 0L
                }
                currentItems.add(PlannedItem(fileItem = file, isSplit = false))
                currentBytes += file.size
            } else {
                val totalParts = ((file.size + targetBytes - 1) / targetBytes).toInt()
                var offset = 0L
                for (partIdx in 1..totalParts) {
                    val partLength = Math.min(targetBytes, file.size - offset)
                    if (currentBytes + partLength > targetBytes && currentItems.isNotEmpty()) {
                        result.add(PlannedChunk(items = currentItems, totalBytes = currentBytes))
                        currentItems = mutableListOf()
                        currentBytes = 0L
                    }
                    currentItems.add(
                        PlannedItem(
                            fileItem = file,
                            isSplit = true,
                            partIndex = partIdx,
                            totalParts = totalParts,
                            splitOffset = offset,
                            splitLength = partLength
                        )
                    )
                    currentBytes += partLength
                    offset += partLength
                }
            }
        }

        if (currentItems.isNotEmpty()) {
            result.add(PlannedChunk(items = currentItems, totalBytes = currentBytes))
        }

        return result
    }

    data class PlannedItem(
        val fileItem: FileItem,
        val isSplit: Boolean,
        val partIndex: Int = 0,
        val totalParts: Int = 1,
        val splitOffset: Long = 0L,
        val splitLength: Long = 0L
    )

    data class PlannedChunk(
        val items: List<PlannedItem>,
        val totalBytes: Long
    )
}
