package com.chunkcompress.app.core

import java.io.File
import java.util.UUID

/**
 * Status of an individual file within the transactional compression workflow.
 */
enum class FileProcessStatus {
    PENDING,
    ARCHIVED,
    VERIFIED,
    DELETED,
    MODIFIED_SKIPPED,
    FAILED
}

/**
 * File item representing an original source file.
 */
data class FileItem(
    val file: File,
    val relativePath: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Metadata for a file entry within a chunk manifest.
 */
data class ManifestFileEntry(
    val relativePath: String,
    val originalSize: Long,
    val sha256: String,
    val lastModified: Long,
    var status: FileProcessStatus = FileProcessStatus.PENDING,
    val isSplit: Boolean = false,
    val partIndex: Int = 0,
    val totalParts: Int = 1,
    val splitPartFileName: String? = null,
    var deletedAt: Long? = null
)

/**
 * Information regarding an individual chunk archive (.zip).
 */
data class ChunkInfo(
    val chunkIndex: Int,
    val archiveFileName: String,
    var archiveSizeBytes: Long = 0L,
    var archiveSha256: String? = null,
    var totalUncompressedBytes: Long = 0L,
    var fileCount: Int = 0,
    var isVerified: Boolean = false,
    var verifiedAt: Long? = null,
    val files: MutableList<ManifestFileEntry> = mutableListOf()
)

/**
 * Version 2 Manifest tracking the complete compression state.
 */
data class ChunkManifest(
    val version: Int = 2,
    val appVersion: String = "2.0.0",
    val sessionId: String = UUID.randomUUID().toString(),
    val sourceFolderName: String,
    val sourceAbsolutePath: String = "",
    val outputAbsolutePath: String = "",
    var totalOriginalBytes: Long = 0L,
    var totalFiles: Int = 0,
    val targetChunkBytes: Long,
    val compressionLevel: Int = 6,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var isCompleted: Boolean = false,
    val chunks: MutableList<ChunkInfo> = mutableListOf()
)

/**
 * Pre-flight safety inspection report before touching any file.
 */
data class PreFlightReport(
    val totalOriginalBytes: Long,
    val totalFiles: Int,
    val availableFreeBytes: Long,
    val targetChunkBytes: Long,
    val largestFile: FileItem?,
    val requiredMinimumFreeBytes: Long,
    val isSafeToProceed: Boolean,
    val isCrossVolume: Boolean = false,
    val canResume: Boolean = false,
    val resumedCompletedChunks: Int = 0,
    val resumedReclaimedBytes: Long = 0L,
    val warnings: List<String> = emptyList()
)

/**
 * Configuration for the compression engine.
 */
data class CompressionConfig(
    val targetChunkBytes: Long = 1024L * 1024L * 1024L, // 1 GB default
    val compressionLevel: Int = 6, // 0 = Store, 1 = Fast, 6 = Balanced, 9 = Maximum
    val verifySha256: Boolean = true, // Mandatory for safe deletion
    val deleteSourceAfterVerified: Boolean = false, // CRITICAL: DISABLED BY DEFAULT FOR DATA SAFETY
    val bufferSizeBytes: Int = 256 * 1024, // 256 KB high-throughput buffer
    val emergencyReserveBytes: Long = 300L * 1024 * 1024, // 300 MB emergency reserve
    val resumeSessionIfPossible: Boolean = true,
    val excludedExtensions: Set<String> = emptySet(),
    val excludeHiddenFiles: Boolean = false,
    val excludeEmptyFiles: Boolean = false
)

/**
 * Strategy for resolving existing destination file conflicts during decompression.
 */
enum class ConflictStrategy {
    OVERWRITE,
    SKIP,
    RENAME
}

/**
 * Configuration for the decompression engine.
 */
data class DecompressionConfig(
    val conflictStrategy: ConflictStrategy = ConflictStrategy.RENAME,
    val deleteChunkAfterExtract: Boolean = false, // Low-storage restore mode
    val verifyOnly: Boolean = false, // Verify archives without writing extracted files
    val selectedFiles: Set<String>? = null, // null = extract all
    val selectedChunkIndexes: Set<Int>? = null, // null = extract all
    val bufferSizeBytes: Int = 256 * 1024
)

/**
 * Session record persisted in historical session database.
 */
data class SessionRecord(
    val sessionId: String,
    val timestamp: Long,
    val sourcePath: String,
    val destinationPath: String,
    val totalOriginalBytes: Long,
    val compressedSizeBytes: Long,
    val totalFiles: Int,
    val totalChunks: Int,
    val reclaimedBytes: Long,
    val isCompleted: Boolean,
    val isDecompression: Boolean = false,
    val statusText: String
)

/**
 * Comprehensive engine lifecycle states.
 */
sealed class EngineState {
    object Idle : EngineState()
    data class Scanning(val filesFound: Int, val bytesFound: Long) : EngineState()
    data class Planning(val totalChunks: Int) : EngineState()
    data class Compressing(
        val chunkIndex: Int,
        val totalChunks: Int,
        val currentFileName: String,
        val chunkBytesProcessed: Long,
        val chunkBytesTotal: Long
    ) : EngineState()
    data class Verifying(
        val chunkIndex: Int,
        val totalChunks: Int,
        val currentFileName: String,
        val filesVerified: Int,
        val totalFilesInChunk: Int
    ) : EngineState()
    data class DeletingSource(
        val chunkIndex: Int,
        val totalChunks: Int,
        val filesDeleted: Int,
        val totalFilesInChunk: Int
    ) : EngineState()
    data class Extracting(
        val chunkIndex: Int,
        val totalChunks: Int,
        val currentFileName: String,
        val filesExtracted: Int,
        val totalFiles: Int
    ) : EngineState()
    data class PausedLowStorage(
        val currentFreeBytes: Long,
        val requiredBufferBytes: Long,
        val message: String
    ) : EngineState()
    object PausedUser : EngineState()
    data class Completed(
        val totalChunks: Int,
        val totalOriginalBytes: Long,
        val totalReclaimedBytes: Long,
        val timeTakenMs: Long
    ) : EngineState()
    data class Error(val message: String, val cause: Throwable? = null) : EngineState()
    object Cancelled : EngineState()
}

/**
 * Live progress snapshot used by UI and Notifications.
 */
data class ProgressSnapshot(
    val state: EngineState = EngineState.Idle,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val currentFileName: String = "",
    val chunkProgressPercent: Float = 0f,
    val totalProgressPercent: Float = 0f,
    val totalBytesProcessed: Long = 0L,
    val totalCompressedBytesWritten: Long = 0L,
    val totalOriginalBytes: Long = 0L,
    val bytesReclaimed: Long = 0L,
    val currentSpeedBytesPerSec: Long = 0L,
    val elapsedMs: Long = 0L,
    val estimatedRemainingMs: Long = 0L,
    val isResumed: Boolean = false,
    val logs: List<LogMessage> = emptyList()
)

/**
 * Log message with severity and timestamp.
 */
data class LogMessage(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

enum class LogLevel {
    INFO, SUCCESS, WARNING, ERROR
}
