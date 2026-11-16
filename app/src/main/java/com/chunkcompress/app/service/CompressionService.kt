package com.chunkcompress.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chunkcompress.app.MainActivity
import com.chunkcompress.app.R
import com.chunkcompress.app.core.ArchiveMode
import com.chunkcompress.app.core.CompressionConfig
import com.chunkcompress.app.core.CompressionEngine
import com.chunkcompress.app.core.ConflictStrategy
import com.chunkcompress.app.core.DecompressionConfig
import com.chunkcompress.app.core.DecompressionEngine
import com.chunkcompress.app.core.EngineState
import com.chunkcompress.app.core.ManifestManager
import com.chunkcompress.app.core.ProgressSnapshot
import com.chunkcompress.app.core.SessionRecord
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.core.history.HistoryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class CompressionService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var workerJob: Job? = null
    private val operationMutex = Mutex()

    private var wakeLock: PowerManager.WakeLock? = null
    private var compressionEngine: CompressionEngine? = null
    private var decompressionEngine: DecompressionEngine? = null
    private var historyManager: HistoryManager? = null

    companion object {
        const val CHANNEL_ID = "chunk_compression_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_COMPRESS = "ACTION_START_COMPRESS"
        const val ACTION_START_DECOMPRESS = "ACTION_START_DECOMPRESS"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_CANCEL = "ACTION_CANCEL"

        const val EXTRA_ARCHIVE_MODE = "EXTRA_ARCHIVE_MODE"
        const val EXTRA_SOURCE_PATH = "EXTRA_SOURCE_PATH"
        const val EXTRA_DEST_PATH = "EXTRA_DEST_PATH"
        const val EXTRA_CHUNK_SIZE = "EXTRA_CHUNK_SIZE"
        const val EXTRA_COMPRESSION_LEVEL = "EXTRA_COMPRESSION_LEVEL"
        const val EXTRA_VERIFY_SHA256 = "EXTRA_VERIFY_SHA256"
        const val EXTRA_DELETE_SOURCE = "EXTRA_DELETE_SOURCE"
        const val EXTRA_EMERGENCY_RESERVE = "EXTRA_EMERGENCY_RESERVE"
        const val EXTRA_CONFLICT_STRATEGY = "EXTRA_CONFLICT_STRATEGY"
        const val EXTRA_DELETE_CHUNK_EXTRACT = "EXTRA_DELETE_CHUNK_EXTRACT"
        const val EXTRA_VERIFY_ONLY = "EXTRA_VERIFY_ONLY"

        private val _progressFlow = MutableStateFlow(ProgressSnapshot())
        val progressFlow = _progressFlow.asStateFlow()

        @Volatile
        var isServiceRunning = false
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): CompressionService = this@CompressionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        historyManager = HistoryManager(this)
        createNotificationChannel()
        acquireWakeLock()
        isServiceRunning = true
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChunkCompress::ExecutionWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(8 * 3600 * 1000L) // 8 hours max safety limit
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COMPRESS -> {
                val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: return START_NOT_STICKY
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH) ?: return START_NOT_STICKY
                val modeName = intent.getStringExtra(EXTRA_ARCHIVE_MODE) ?: ArchiveMode.SINGLE_ZIP.name
                val archiveMode = try { ArchiveMode.valueOf(modeName) } catch (e: Exception) { ArchiveMode.SINGLE_ZIP }
                val chunkSize = intent.getLongExtra(EXTRA_CHUNK_SIZE, 1024L * 1024 * 1024)
                val compLevel = intent.getIntExtra(EXTRA_COMPRESSION_LEVEL, 1)
                val verifySha = intent.getBooleanExtra(EXTRA_VERIFY_SHA256, true)
                val deleteSource = intent.getBooleanExtra(EXTRA_DELETE_SOURCE, false)
                val reserveBytes = intent.getLongExtra(EXTRA_EMERGENCY_RESERVE, 300L * 1024 * 1024)

                startCompression(File(sourcePath), File(destPath), archiveMode, chunkSize, compLevel, verifySha, deleteSource, reserveBytes)
            }
            ACTION_START_DECOMPRESS -> {
                val sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: return START_NOT_STICKY
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH) ?: return START_NOT_STICKY
                val strategyName = intent.getStringExtra(EXTRA_CONFLICT_STRATEGY) ?: ConflictStrategy.RENAME.name
                val strategy = try { ConflictStrategy.valueOf(strategyName) } catch (e: Exception) { ConflictStrategy.RENAME }
                val deleteChunks = intent.getBooleanExtra(EXTRA_DELETE_CHUNK_EXTRACT, false)
                val verifyOnly = intent.getBooleanExtra(EXTRA_VERIFY_ONLY, false)

                startDecompression(File(sourcePath), File(destPath), strategy, deleteChunks, verifyOnly)
            }
            ACTION_PAUSE -> {
                compressionEngine?.setPaused(true)
                decompressionEngine?.setPaused(true)
                _progressFlow.value = _progressFlow.value.copy(state = EngineState.PausedUser)
                updateNotification(_progressFlow.value)
            }
            ACTION_RESUME -> {
                compressionEngine?.setPaused(false)
                decompressionEngine?.setPaused(false)
                updateNotification(_progressFlow.value)
            }
            ACTION_CANCEL -> {
                cancelCurrentOperation()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCompression(
        source: File,
        dest: File,
        archiveMode: ArchiveMode,
        chunkSize: Long,
        level: Int,
        verifySha: Boolean,
        deleteSource: Boolean,
        reserveBytes: Long
    ) {
        val notification = createNotification("Starting compression...", 0, 0, false)
        startForeground(NOTIFICATION_ID, notification)

        workerJob?.cancel()
        workerJob = serviceScope.launch {
            operationMutex.withLock {
                try {
                    val config = CompressionConfig(
                        archiveMode = archiveMode,
                        targetChunkBytes = chunkSize,
                        compressionLevel = level,
                        verifySha256 = verifySha,
                        deleteSourceAfterVerified = deleteSource,
                        emergencyReserveBytes = reserveBytes
                    )

                    compressionEngine = CompressionEngine(
                        sourceFolder = source,
                        outputDir = dest,
                        config = config
                    ) { snapshot ->
                        _progressFlow.value = snapshot
                        updateNotification(snapshot)
                    }

                    val manifest = compressionEngine?.execute()

                    // Record completed session in history
                    if (manifest != null) {
                        historyManager?.recordSession(
                            SessionRecord(
                                sessionId = manifest.sessionId,
                                timestamp = manifest.createdAt,
                                sourcePath = source.absolutePath,
                                destinationPath = dest.absolutePath,
                                totalOriginalBytes = manifest.totalOriginalBytes,
                                compressedSizeBytes = manifest.chunks.sumOf { it.archiveSizeBytes },
                                totalFiles = manifest.totalFiles,
                                totalChunks = manifest.chunks.size,
                                reclaimedBytes = _progressFlow.value.bytesReclaimed,
                                isCompleted = true,
                                isDecompression = false,
                                statusText = "COMPLETED"
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    _progressFlow.value = _progressFlow.value.copy(state = EngineState.Cancelled)
                } catch (e: Exception) {
                    _progressFlow.value = _progressFlow.value.copy(
                        state = EngineState.Error(e.message ?: "Compression failed", e)
                    )
                } finally {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }
    }

    private fun startDecompression(
        chunksDir: File,
        dest: File,
        conflictStrategy: ConflictStrategy,
        deleteChunks: Boolean,
        verifyOnly: Boolean
    ) {
        val notification = createNotification("Starting restoration...", 0, 0, false)
        startForeground(NOTIFICATION_ID, notification)

        workerJob?.cancel()
        workerJob = serviceScope.launch {
            operationMutex.withLock {
                try {
                    val manifest = ManifestManager.loadManifest(chunksDir)
                        ?: throw IllegalArgumentException("No valid chunk_manifest.json found in ${chunksDir.absolutePath}")

                    val config = DecompressionConfig(
                        conflictStrategy = conflictStrategy,
                        deleteChunkAfterExtract = deleteChunks,
                        verifyOnly = verifyOnly
                    )

                    decompressionEngine = DecompressionEngine(
                        manifest = manifest,
                        chunksDir = chunksDir,
                        destinationDir = dest,
                        config = config
                    ) { snapshot ->
                        _progressFlow.value = snapshot
                        updateNotification(snapshot)
                    }

                    decompressionEngine?.execute()

                    // Record completed restoration in history
                    historyManager?.recordSession(
                        SessionRecord(
                            sessionId = manifest.sessionId,
                            timestamp = System.currentTimeMillis(),
                            sourcePath = chunksDir.absolutePath,
                            destinationPath = dest.absolutePath,
                            totalOriginalBytes = manifest.totalOriginalBytes,
                            compressedSizeBytes = manifest.chunks.sumOf { it.archiveSizeBytes },
                            totalFiles = manifest.totalFiles,
                            totalChunks = manifest.chunks.size,
                            reclaimedBytes = 0L,
                            isCompleted = true,
                            isDecompression = true,
                            statusText = if (verifyOnly) "VERIFIED" else "RESTORED"
                        )
                    )
                } catch (e: CancellationException) {
                    _progressFlow.value = _progressFlow.value.copy(state = EngineState.Cancelled)
                } catch (e: Exception) {
                    _progressFlow.value = _progressFlow.value.copy(
                        state = EngineState.Error(e.message ?: "Restoration failed", e)
                    )
                } finally {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }
    }

    fun cancelCurrentOperation() {
        compressionEngine?.cancel()
        decompressionEngine?.cancel()
        workerJob?.cancel()
        _progressFlow.value = _progressFlow.value.copy(state = EngineState.Cancelled)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int, max: Int, isPaused: Boolean): android.app.Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CompressionService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CompressionService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChunkCompress Engine")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        return builder.build()
    }

    private fun updateNotification(snapshot: ProgressSnapshot) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val percent = (snapshot.totalProgressPercent * 100).toInt()
        val isPaused = snapshot.state is EngineState.PausedUser || snapshot.state is EngineState.PausedLowStorage

        val text = when (val s = snapshot.state) {
            is EngineState.Compressing -> "Chunk ${s.chunkIndex}/${s.totalChunks} | ${StorageUtils.formatBytes(snapshot.currentSpeedBytesPerSec)}/s"
            is EngineState.Verifying -> "Verifying Chunk ${s.chunkIndex}/${s.totalChunks} (SHA-256)"
            is EngineState.DeletingSource -> "Reclaiming space: ${StorageUtils.formatBytes(snapshot.bytesReclaimed)}"
            is EngineState.Extracting -> "Extracting ${s.chunkIndex}/${s.totalChunks} | ${StorageUtils.formatBytes(snapshot.currentSpeedBytesPerSec)}/s"
            is EngineState.PausedLowStorage -> "PAUSED: Storage low (< 300MB)"
            is EngineState.PausedUser -> "PAUSED by user"
            is EngineState.Completed -> "Task Finished! Freed ${StorageUtils.formatBytes(snapshot.bytesReclaimed)}"
            is EngineState.Error -> "Failed: ${s.message}"
            else -> "Processing..."
        }

        val notification = createNotification(text, percent, 100, isPaused)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        // Android 15 (API 35) Foreground Service Timeout lifecycle callback
        cancelCurrentOperation()
        stopSelf(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        releaseWakeLock()
    }
}
