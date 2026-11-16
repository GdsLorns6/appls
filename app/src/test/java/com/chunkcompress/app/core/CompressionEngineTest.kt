package com.chunkcompress.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class CompressionEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSha256Calculation() {
        val testFile = tempFolder.newFile("test_hash.txt")
        testFile.writeText("Hello ChunkCompress 50GB Test String")

        val hash1 = HashUtils.sha256(testFile)
        val hash2 = HashUtils.sha256(testFile)

        assertNotNull(hash1)
        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
    }

    @Test
    fun testPreFlightInspection() {
        val sourceDir = tempFolder.newFolder("source_preflight")
        val outputDir = tempFolder.newFolder("output_preflight")

        val file1 = File(sourceDir, "test1.bin")
        file1.writeBytes(ByteArray(1024 * 500)) // 500 KB

        val config = CompressionConfig(
            targetChunkBytes = 1024 * 1024L,
            emergencyReserveBytes = 100 * 1024L
        )

        val report = CompressionEngine.inspectPreFlight(sourceDir, outputDir, config)
        assertTrue(report.totalFiles == 1)
        assertTrue(report.totalOriginalBytes == 1024 * 500L)
        assertNotNull(report.largestFile)
        assertEquals("test1.bin", report.largestFile?.file?.name)
    }

    @Test
    fun testIncrementalCompressionAndDecompressionWithVerification() = runBlocking {
        val sourceDir = tempFolder.newFolder("source_data")
        val outputDir = tempFolder.newFolder("output_chunks")
        val restoreDir = tempFolder.newFolder("restored_data")

        val fileHashes = mutableMapOf<String, String>()
        val random = Random(42)

        for (i in 1..8) {
            val file = File(sourceDir, "file_$i.bin")
            val bytes = ByteArray(256 * 1024) // 256 KB each
            random.nextBytes(bytes)
            FileOutputStream(file).use { it.write(bytes) }
            fileHashes[file.name] = HashUtils.sha256(file)
        }

        val totalOriginalFiles = sourceDir.listFiles()?.size ?: 0
        assertEquals(8, totalOriginalFiles)

        // Config: 512 KB target chunk size so it splits 8 files into multiple chunks
        val config = CompressionConfig(
            targetChunkBytes = 512 * 1024L,
            compressionLevel = 1,
            verifySha256 = true,
            deleteSourceAfterVerified = true
        )

        val engine = CompressionEngine(
            sourceFolder = sourceDir,
            outputDir = outputDir,
            config = config
        ) { /* progress */ }

        val manifest = engine.execute()

        // 1. Verify manifest
        assertTrue(manifest.isCompleted)
        assertTrue(manifest.chunks.size >= 2)

        // 2. Verify all chunks were marked as verified
        for (chunk in manifest.chunks) {
            assertTrue("Chunk ${chunk.chunkIndex} must be verified", chunk.isVerified)
            val chunkFile = File(outputDir, chunk.archiveFileName)
            assertTrue(chunkFile.exists())
        }

        // 3. Verify original files were safely deleted from source to reclaim space
        val remainingFiles = sourceDir.listFiles()?.filter { it.isFile }?.size ?: 0
        assertEquals("Source files should be deleted to free space after verification", 0, remainingFiles)

        // 4. Test Decompression and Restoration
        val loadedManifest = ManifestManager.loadManifest(outputDir)
        assertNotNull(loadedManifest)

        val decompEngine = DecompressionEngine(
            manifest = loadedManifest!!,
            chunksDir = outputDir,
            destinationDir = restoreDir,
            config = DecompressionConfig(deleteChunkAfterExtract = true)
        ) { /* progress */ }

        decompEngine.execute()

        // 5. Verify every single restored file matches original hash exactly!
        for ((fileName, originalHash) in fileHashes) {
            val restoredFile = File(restoreDir, fileName)
            assertTrue("Restored file $fileName should exist", restoredFile.exists())
            val restoredHash = HashUtils.sha256(restoredFile)
            assertEquals("SHA-256 for $fileName must match 100%!", originalHash, restoredHash)
        }

        // 6. Verify that in deleteChunkAfterExtract mode, chunk archives were deleted
        for (chunk in manifest.chunks) {
            val chunkFile = File(outputDir, chunk.archiveFileName)
            assertFalse("Chunk file should be deleted in low-space restore mode", chunkFile.exists())
        }
    }

    @Test
    fun testManifestSerializationAndDeserialization() {
        val testDir = tempFolder.newFolder("manifest_test")
        val manifest = ChunkManifest(
            sourceFolderName = "My50GBFolder",
            totalOriginalBytes = 53687091200L,
            totalFiles = 1250,
            targetChunkBytes = 2147483648L
        )
        val chunk1 = ChunkInfo(
            chunkIndex = 1,
            archiveFileName = "chunk_001.zip",
            archiveSizeBytes = 1800000000L,
            totalUncompressedBytes = 2147483648L,
            fileCount = 50,
            isVerified = true
        )
        chunk1.files.add(
            ManifestFileEntry(
                relativePath = "sub/data.bin",
                originalSize = 1048576L,
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                lastModified = 1620000000000L
            )
        )
        manifest.chunks.add(chunk1)

        val savedFile = ManifestManager.saveManifest(manifest, testDir)
        assertTrue(savedFile.exists())

        val loaded = ManifestManager.loadManifest(testDir)
        assertNotNull(loaded)
        assertEquals("My50GBFolder", loaded?.sourceFolderName)
        assertEquals(53687091200L, loaded?.totalOriginalBytes)
        assertEquals(1, loaded?.chunks?.size)
        assertEquals(true, loaded?.chunks?.get(0)?.isVerified)
        assertEquals("sub/data.bin", loaded?.chunks?.get(0)?.files?.get(0)?.relativePath)
    }
}
