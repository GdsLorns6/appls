package com.chunkcompress.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chunkcompress.app.core.ArchiveMode
import com.chunkcompress.app.core.CompressionConfig
import com.chunkcompress.app.core.CompressionEngine
import com.chunkcompress.app.core.ConflictStrategy
import com.chunkcompress.app.core.DecompressionConfig
import com.chunkcompress.app.core.DecompressionEngine
import com.chunkcompress.app.core.EngineState
import com.chunkcompress.app.core.ManifestManager
import com.chunkcompress.app.core.PreFlightReport
import com.chunkcompress.app.core.ProgressSnapshot
import com.chunkcompress.app.core.SessionRecord
import com.chunkcompress.app.core.StorageUtils
import com.chunkcompress.app.core.history.HistoryManager
import com.chunkcompress.app.service.CompressionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val historyManager = HistoryManager(application)

    // Storage info
    private val _freeDiskSpace = MutableStateFlow(0L)
    val freeDiskSpace: StateFlow<Long> = _freeDiskSpace.asStateFlow()

    private val _totalDiskSpace = MutableStateFlow(0L)
    val totalDiskSpace: StateFlow<Long> = _totalDiskSpace.asStateFlow()

    // Compression Source & Destination
    private val _sourceDirectory = MutableStateFlow<File?>(null)
    val sourceDirectory: StateFlow<File?> = _sourceDirectory.asStateFlow()

    private val _sourceFolderStats = MutableStateFlow<Pair<Long, Int>>(Pair(0L, 0))
    val sourceFolderStats: StateFlow<Pair<Long, Int>> = _sourceFolderStats.asStateFlow()

    private val _isScanningSource = MutableStateFlow(false)
    val isScanningSource: StateFlow<Boolean> = _isScanningSource.asStateFlow()

    private val _destinationDirectory = MutableStateFlow<File?>(null)
    val destinationDirectory: StateFlow<File?> = _destinationDirectory.asStateFlow()

    // Compression options
    private val _archiveMode = MutableStateFlow(ArchiveMode.SINGLE_ZIP) // Single Complete ZIP default!
    val archiveMode: StateFlow<ArchiveMode> = _archiveMode.asStateFlow()

    private val _chunkSizeBytes = MutableStateFlow(1024L * 1024L * 1024L) // 1 GB default (for chunked mode)
    val chunkSizeBytes: StateFlow<Long> = _chunkSizeBytes.asStateFlow()

    private val _compressionLevel = MutableStateFlow(1) // 0=Store, 1=Fast (default), 6=Balanced, 9=Max
    val compressionLevel: StateFlow<Int> = _compressionLevel.asStateFlow()

    private val _verifySha256 = MutableStateFlow(true)
    val verifySha256: StateFlow<Boolean> = _verifySha256.asStateFlow()

    private val _deleteSourceAfterVerified = MutableStateFlow(false) // CRITICAL: DISABLED BY DEFAULT
    val deleteSourceAfterVerified: StateFlow<Boolean> = _deleteSourceAfterVerified.asStateFlow()

    private val _emergencyReserveBytes = MutableStateFlow(300L * 1024 * 1024) // 300 MB
    val emergencyReserveBytes: StateFlow<Long> = _emergencyReserveBytes.asStateFlow()

    private val _excludeHiddenFiles = MutableStateFlow(false)
    val excludeHiddenFiles: StateFlow<Boolean> = _excludeHiddenFiles.asStateFlow()

    // Pre-flight inspection report
    private val _preFlightReport = MutableStateFlow<PreFlightReport?>(null)
    val preFlightReport: StateFlow<PreFlightReport?> = _preFlightReport.asStateFlow()

    // Decompression
    private val _decompressSourceDir = MutableStateFlow<File?>(null)
    val decompressSourceDir: StateFlow<File?> = _decompressSourceDir.asStateFlow()

    private val _decompressDestDir = MutableStateFlow<File?>(null)
    val decompressDestDir: StateFlow<File?> = _decompressDestDir.asStateFlow()

    private val _conflictStrategy = MutableStateFlow(ConflictStrategy.RENAME)
    val conflictStrategy: StateFlow<ConflictStrategy> = _conflictStrategy.asStateFlow()

    private val _deleteChunksOnExtract = MutableStateFlow(false)
    val deleteChunksOnExtract: StateFlow<Boolean> = _deleteChunksOnExtract.asStateFlow()

    private val _verifyOnlyMode = MutableStateFlow(false)
    val verifyOnlyMode: StateFlow<Boolean> = _verifyOnlyMode.asStateFlow()

    // Navigation (0: Home, 1: Compress, 2: Decompress, 3: History, 4: Settings)
    private val _currentNavIndex = MutableStateFlow(0)
    val currentNavIndex: StateFlow<Int> = _currentNavIndex.asStateFlow()

    // Sessions history
    private val _historySessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val historySessions: StateFlow<List<SessionRecord>> = _historySessions.asStateFlow()

    // Progress flow from service
    val progressFlow: StateFlow<ProgressSnapshot> = CompressionService.progressFlow

    init {
        refreshStorageInfo()
        loadHistory()
        val defaultDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (defaultDownloads != null && defaultDownloads.exists()) {
            _destinationDirectory.value = defaultDownloads
            _decompressDestDir.value = defaultDownloads
        }
    }

    fun setNavIndex(index: Int) {
        _currentNavIndex.value = index
    }

    fun refreshStorageInfo() {
        val root = Environment.getExternalStorageDirectory()
        _freeDiskSpace.value = StorageUtils.getAvailableFreeSpaceBytes(root)
        _totalDiskSpace.value = StorageUtils.getTotalSpaceBytes(root)
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            _historySessions.value = historyManager.getSessions()
        }
    }

    fun deleteHistoryRecord(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            historyManager.removeSession(sessionId)
            loadHistory()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyManager.clearAllHistory()
            loadHistory()
        }
    }

    fun getSessionReport(record: SessionRecord): String {
        return historyManager.generateSessionReport(record)
    }

    fun setSourceDirectory(dir: File) {
        _sourceDirectory.value = dir
        _isScanningSource.value = true
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                StorageUtils.calculateFolderStats(dir, excludeHidden = _excludeHiddenFiles.value)
            }
            _sourceFolderStats.value = stats
            _isScanningSource.value = false
            runPreFlightInspection()
        }
    }

    fun setDestinationDirectory(dir: File) {
        _destinationDirectory.value = dir
        runPreFlightInspection()
    }

    fun setArchiveMode(mode: ArchiveMode) {
        _archiveMode.value = mode
        runPreFlightInspection()
    }

    fun setChunkSize(bytes: Long) {
        _chunkSizeBytes.value = bytes
        runPreFlightInspection()
    }

    fun autoRecommendChunkSize() {
        val free = _freeDiskSpace.value
        val recommended = CompressionEngine.recommendChunkSize(free)
        setChunkSize(recommended)
    }

    fun setCompressionLevel(level: Int) {
        _compressionLevel.value = level
    }

    fun setVerifySha256(verify: Boolean) {
        _verifySha256.value = verify
    }

    fun setDeleteSourceAfterVerified(delete: Boolean) {
        _deleteSourceAfterVerified.value = delete
    }

    fun setEmergencyReserve(bytes: Long) {
        _emergencyReserveBytes.value = bytes
        runPreFlightInspection()
    }

    fun setExcludeHiddenFiles(exclude: Boolean) {
        _excludeHiddenFiles.value = exclude
        _sourceDirectory.value?.let { setSourceDirectory(it) }
    }

    fun setDecompressSourceDir(dir: File) {
        _decompressSourceDir.value = dir
    }

    fun setDecompressDestDir(dir: File) {
        _decompressDestDir.value = dir
    }

    fun setConflictStrategy(strategy: ConflictStrategy) {
        _conflictStrategy.value = strategy
    }

    fun setDeleteChunksOnExtract(delete: Boolean) {
        _deleteChunksOnExtract.value = delete
    }

    fun setVerifyOnlyMode(verifyOnly: Boolean) {
        _verifyOnlyMode.value = verifyOnly
    }

    fun runPreFlightInspection() {
        val source = _sourceDirectory.value ?: return
        val dest = _destinationDirectory.value ?: source.parentFile ?: source

        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val config = CompressionConfig(
                    archiveMode = _archiveMode.value,
                    targetChunkBytes = _chunkSizeBytes.value,
                    compressionLevel = _compressionLevel.value,
                    verifySha256 = _verifySha256.value,
                    deleteSourceAfterVerified = _deleteSourceAfterVerified.value,
                    emergencyReserveBytes = _emergencyReserveBytes.value,
                    excludeHiddenFiles = _excludeHiddenFiles.value
                )
                CompressionEngine.inspectPreFlight(source, dest, config)
            }
            _preFlightReport.value = report
        }
    }

    fun startCompression() {
        val source = _sourceDirectory.value ?: return
        val dest = _destinationDirectory.value ?: source.parentFile ?: source

        val intent = Intent(getApplication(), CompressionService::class.java).apply {
            action = CompressionService.ACTION_START_COMPRESS
            putExtra(CompressionService.EXTRA_ARCHIVE_MODE, _archiveMode.value.name)
            putExtra(CompressionService.EXTRA_SOURCE_PATH, source.absolutePath)
            putExtra(CompressionService.EXTRA_DEST_PATH, dest.absolutePath)
            putExtra(CompressionService.EXTRA_CHUNK_SIZE, _chunkSizeBytes.value)
            putExtra(CompressionService.EXTRA_COMPRESSION_LEVEL, _compressionLevel.value)
            putExtra(CompressionService.EXTRA_VERIFY_SHA256, _verifySha256.value)
            putExtra(CompressionService.EXTRA_DELETE_SOURCE, _deleteSourceAfterVerified.value)
            putExtra(CompressionService.EXTRA_EMERGENCY_RESERVE, _emergencyReserveBytes.value)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun startDecompression() {
        val source = _decompressSourceDir.value ?: return
        val dest = _decompressDestDir.value ?: return

        val intent = Intent(getApplication(), CompressionService::class.java).apply {
            action = CompressionService.ACTION_START_DECOMPRESS
            putExtra(CompressionService.EXTRA_SOURCE_PATH, source.absolutePath)
            putExtra(CompressionService.EXTRA_DEST_PATH, dest.absolutePath)
            putExtra(CompressionService.EXTRA_CONFLICT_STRATEGY, _conflictStrategy.value.name)
            putExtra(CompressionService.EXTRA_DELETE_CHUNK_EXTRACT, _deleteChunksOnExtract.value)
            putExtra(CompressionService.EXTRA_VERIFY_ONLY, _verifyOnlyMode.value)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun pauseOperation() {
        val intent = Intent(getApplication(), CompressionService::class.java).apply {
            action = CompressionService.ACTION_PAUSE
        }
        getApplication<Application>().startService(intent)
    }

    fun resumeOperation() {
        val intent = Intent(getApplication(), CompressionService::class.java).apply {
            action = CompressionService.ACTION_RESUME
        }
        getApplication<Application>().startService(intent)
    }

    fun cancelOperation() {
        val intent = Intent(getApplication(), CompressionService::class.java).apply {
            action = CompressionService.ACTION_CANCEL
        }
        getApplication<Application>().startService(intent)
    }
}
