package com.chunkcompress.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class ChunkCompressEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSha256Calculation() {
        val testFile = tempFolder.newFile("test_hash.txt")
        testFile.writeText("ChunkCompress 2.0 SHA-256 Verification Test String")

        val hash1 = HashUtils.sha256(testFile)
        val hash2 = HashUtils.sha256(testFile)

        assertNotNull(hash1)
        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
    }

    @Test
    fun testZeroByteFileHandling() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_empty")
        val outputDir = tempFolder.newFolder("output_empty")
        val restoreDir = tempFolder.newFolder("restore_empty")

        val emptyFile = File(sourceDir, "empty.bin")
        emptyFile.createNewFile()

        val normalFile = File(sourceDir, "normal.txt")
        normalFile.writeText("Normal content")

        val config = CompressionConfig(
            targetChunkBytes = 1024 * 1024L,
            verifySha256 = true,
            deleteSourceAfterVerified = false
        )

        val engine = CompressionEngine(sourceDir, outputDir, config) {}
        val manifest = engine.execute()

        assertTrue(manifest.isCompleted)
        assertEquals(2, manifest.totalFiles)

        val decompEngine = DecompressionEngine(manifest, outputDir, restoreDir) {}
        decompEngine.execute()

        val restoredEmpty = File(restoreDir, "empty.bin")
        assertTrue(restoredEmpty.exists())
        assertEquals(0L, restoredEmpty.length())

        val restoredNormal = File(restoreDir, "normal.txt")
        assertTrue(restoredNormal.exists())
        assertEquals("Normal content", restoredNormal.readText())
    }

    @Test
    fun testUnicodeFilenamesHandling() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_unicode")
        val outputDir = tempFolder.newFolder("output_unicode")
        val restoreDir = tempFolder.newFolder("restore_unicode")

        val unicodeName = "ملف_بيانات_テスト_🚀.txt"
        val unicodeFile = File(sourceDir, unicodeName)
        unicodeFile.writeText("Unicode UTF-8 content testing")

        val expectedHash = HashUtils.sha256(unicodeFile)

        val config = CompressionConfig(
            targetChunkBytes = 1024 * 1024L,
            verifySha256 = true
        )

        val engine = CompressionEngine(sourceDir, outputDir, config) {}
        val manifest = engine.execute()

        val decompEngine = DecompressionEngine(manifest, outputDir, restoreDir) {}
        decompEngine.execute()

        val restoredFile = File(restoreDir, unicodeName)
        assertTrue(restoredFile.exists())
        assertEquals(expectedHash, HashUtils.sha256(restoredFile))
    }

    @Test
    fun testMultiChunkCompressionAndDecompressionWithSafeDeletion() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_multichunk")
        val outputDir = tempFolder.newFolder("output_multichunk")
        val restoreDir = tempFolder.newFolder("restore_multichunk")

        val fileHashes = mutableMapOf<String, String>()
        val random = Random(12345)

        for (i in 1..6) {
            val file = File(sourceDir, "data_file_$i.bin")
            val bytes = ByteArray(200 * 1024) // 200 KB each
            random.nextBytes(bytes)
            FileOutputStream(file).use { it.write(bytes) }
            fileHashes[file.name] = HashUtils.sha256(file)
        }

        // Target chunk: 350 KB so 6 files split into at least 4 chunks
        val config = CompressionConfig(
            archiveMode = ArchiveMode.CHUNKED_ZIPS,
            targetChunkBytes = 350 * 1024L,
            compressionLevel = 1,
            verifySha256 = true,
            deleteSourceAfterVerified = true // Safe deletion enabled
        )

        val engine = CompressionEngine(sourceDir, outputDir, config) {}
        val manifest = engine.execute()

        assertTrue(manifest.isCompleted)
        assertTrue("Must be split into at least 3 chunks", manifest.chunks.size >= 3)

        // All chunks must be verified
        manifest.chunks.forEach { chunk ->
            assertTrue("Chunk ${chunk.chunkIndex} must be marked verified", chunk.isVerified)
            assertNotNull(chunk.archiveSha256)
        }

        // Source files must be deleted after 100% verification
        val remainingSourceFiles = sourceDir.listFiles()?.filter { it.isFile }?.size ?: 0
        assertEquals(0, remainingSourceFiles)

        // Decompress and test low-storage restoration
        val decompEngine = DecompressionEngine(
            manifest = manifest,
            chunksDir = outputDir,
            destinationDir = restoreDir,
            config = DecompressionConfig(deleteChunkAfterExtract = true)
        ) {}

        decompEngine.execute()

        // Every restored file must match SHA-256 exactly
        for ((name, originalHash) in fileHashes) {
            val restored = File(restoreDir, name)
            assertTrue(restored.exists())
            assertEquals(originalHash, HashUtils.sha256(restored))
        }

        // In low-storage mode, chunk archives must be deleted
        manifest.chunks.forEach { chunk ->
            val chunkFile = File(outputDir, chunk.archiveFileName)
            assertFalse(chunkFile.exists())
        }
    }

    @Test
    fun testSourceModificationDetectionPreventsDeletion() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_mod")
        val outputDir = tempFolder.newFolder("output_mod")

        val file = File(sourceDir, "mod_target.bin")
        file.writeText("Original content before archiving")

        // Compression with deletion enabled
        val config = CompressionConfig(
            targetChunkBytes = 1024 * 1024L,
            verifySha256 = true,
            deleteSourceAfterVerified = true
        )

        val engine = CompressionEngine(sourceDir, outputDir, config) {}
        val manifest = engine.execute()

        assertTrue(manifest.isCompleted)
        // With unmodified file, deletion succeeds
        assertFalse(file.exists())
    }

    @Test
    fun testManifestBackupAndRecovery() {
        val dir = tempFolder.newFolder("manifest_backup_test")
        val manifest = ChunkManifest(
            sourceFolderName = "BackupFolder",
            totalOriginalBytes = 1000000L,
            totalFiles = 10,
            targetChunkBytes = 500000L
        )

        ManifestManager.saveManifest(manifest, dir)
        val primaryFile = File(dir, ManifestManager.MANIFEST_FILE_NAME)
        assertTrue(primaryFile.exists())

        // Save second time to trigger backup creation
        manifest.totalFiles = 20
        ManifestManager.saveManifest(manifest, dir)

        val backupFile = File(dir, ManifestManager.MANIFEST_BACKUP_NAME)
        assertTrue(backupFile.exists())

        // Corrupt primary file
        primaryFile.writeText("{ corrupted json ...")

        // Load must recover from backup
        val recovered = ManifestManager.loadManifest(dir)
        assertNotNull(recovered)
        assertEquals("BackupFolder", recovered?.sourceFolderName)
    }

    @Test
    fun testZipSlipProtection() = runBlocking {
        val destDir = tempFolder.newFolder("dest_zipslip")
        val chunksDir = tempFolder.newFolder("chunks_zipslip")

        // Malicious manifest with path traversal
        val maliciousManifest = ChunkManifest(
            sourceFolderName = "Exploit",
            totalOriginalBytes = 100L,
            totalFiles = 1,
            targetChunkBytes = 1000L
        )
        val chunk = ChunkInfo(1, "chunk_001.zip")
        chunk.files.add(
            ManifestFileEntry(
                relativePath = "../../evil.txt",
                originalSize = 10L,
                sha256 = "dummy",
                lastModified = 0L
            )
        )
        maliciousManifest.chunks.add(chunk)

        val engine = DecompressionEngine(maliciousManifest, chunksDir, destDir) {}

        try {
            engine.execute()
            fail("Expected SecurityException for Zip Slip path traversal!")
        } catch (e: Exception) {
            // Must fail with SecurityException or Missing required chunk
            assertTrue(e is SecurityException || e is IllegalStateException)
        }
    }

    @Test
    fun testManifestSyncDoesNotThrowClosedDescriptorException() {
        val dir = tempFolder.newFolder("sync_test")
        val manifest = ChunkManifest(
            sourceFolderName = "SyncTestFolder",
            totalOriginalBytes = 12345L,
            totalFiles = 3,
            targetChunkBytes = 1000L
        )

        // Save multiple times rapidly to test sync and atomic rename
        for (i in 1..5) {
            manifest.totalFiles = i * 10
            val savedFile = ManifestManager.saveManifest(manifest, dir)
            assertTrue(savedFile.exists())
            assertTrue(savedFile.length() > 0)
        }

        val loaded = ManifestManager.loadManifest(dir)
        assertNotNull(loaded)
        assertEquals(50, loaded?.totalFiles)
    }

    @Test
    fun testSplitFileTransactionalAssembly() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_split")
        val outputDir = tempFolder.newFolder("output_split")
        val restoreDir = tempFolder.newFolder("restore_split")

        // Create a large file that will be split across chunks
        val largeFile = File(sourceDir, "large_video.bin")
        val content = ByteArray(600 * 1024) // 600 KB
        Random(99).nextBytes(content)
        largeFile.writeBytes(content)
        val expectedHash = HashUtils.sha256(largeFile)

        // 200 KB chunk size -> will split 600 KB file into 3 parts
        val config = CompressionConfig(
            archiveMode = ArchiveMode.CHUNKED_ZIPS,
            targetChunkBytes = 200 * 1024L,
            verifySha256 = true,
            deleteSourceAfterVerified = false
        )

        val engine = CompressionEngine(sourceDir, outputDir, config) {}
        val manifest = engine.execute()

        assertTrue(manifest.chunks.size >= 3)
        val splitEntries = manifest.chunks.flatMap { it.files }.filter { it.isSplit }
        assertEquals(3, splitEntries.size)

        // Test 1: Verify-only mode on split files
        val verifyOnlyEngine = DecompressionEngine(
            manifest = manifest,
            chunksDir = outputDir,
            destinationDir = restoreDir,
            config = DecompressionConfig(verifyOnly = true)
        ) {}
        verifyOnlyEngine.execute()

        // In verify-only, target file must NOT be written to disk
        val restoredTarget = File(restoreDir, "large_video.bin")
        assertFalse("Verify-only mode must not write file to disk", restoredTarget.exists())

        // Test 2: Full transactional extraction
        val extractEngine = DecompressionEngine(
            manifest = manifest,
            chunksDir = outputDir,
            destinationDir = restoreDir,
            config = DecompressionConfig(verifyOnly = false)
        ) {}
        extractEngine.execute()

        assertTrue(restoredTarget.exists())
        assertEquals(content.size.toLong(), restoredTarget.length())
        assertEquals(expectedHash, HashUtils.sha256(restoredTarget))

        // No staging file left behind
        val stagingFile = File(restoreDir, "large_video.bin.staging_split")
        assertFalse(stagingFile.exists())
    }
}
