package com.chunkcompress.app.core

import android.os.StatFs
import java.io.File
import java.text.DecimalFormat

object StorageUtils {

    /**
     * Get available free space on the filesystem of the given path in bytes.
     */
    fun getAvailableFreeSpaceBytes(path: File): Long {
        return try {
            val target = getExistingAncestor(path)
            val stat = StatFs(target.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Get total storage space on the filesystem in bytes.
     */
    fun getTotalSpaceBytes(path: File): Long {
        return try {
            val target = getExistingAncestor(path)
            val stat = StatFs(target.absolutePath)
            stat.totalBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun getExistingAncestor(file: File): File {
        var current: File? = file
        while (current != null && !current.exists()) {
            current = current.parentFile
        }
        return current ?: File("/")
    }

    /**
     * Determines if two directories reside on different storage volumes/mount points.
     */
    fun isDifferentStorageVolume(pathA: File, pathB: File): Boolean {
        return try {
            val ancestorA = getExistingAncestor(pathA)
            val ancestorB = getExistingAncestor(pathB)
            val statA = StatFs(ancestorA.absolutePath)
            val statB = StatFs(ancestorB.absolutePath)
            // If total bytes or block size differ, they are almost certainly different storage devices/mounts
            statA.totalBytes != statB.totalBytes || statA.blockSizeLong != statB.blockSizeLong
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Format bytes to human readable format (e.g. 50.00 GB, 1.25 MB).
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return DecimalFormat("#,##0.##").format(value) + " " + units[index]
    }

    /**
     * Recursively computes total size and file count of a folder with optional exclusion filtering.
     */
    fun calculateFolderStats(
        folder: File,
        excludedExtensions: Set<String> = emptySet(),
        excludeHidden: Boolean = false,
        excludeEmpty: Boolean = false
    ): Pair<Long, Int> {
        var totalBytes = 0L
        var fileCount = 0
        if (!folder.exists() || !folder.isDirectory) return Pair(0L, 0)

        folder.walkTopDown()
            .onEnter { dir -> !excludeHidden || !dir.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { file ->
                if (excludeHidden && file.name.startsWith(".")) return@forEach
                if (excludeEmpty && file.length() == 0L) return@forEach
                if (excludedExtensions.isNotEmpty()) {
                    val ext = file.extension.lowercase()
                    if (excludedExtensions.contains(ext)) return@forEach
                }
                totalBytes += file.length()
                fileCount++
            }
        return Pair(totalBytes, fileCount)
    }
}
