package com.chunkcompress.app.core

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {

    private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer

    /**
     * Compute SHA-256 of a File.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return bytesToHex(digest.digest())
    }

    /**
     * Compute SHA-256 of an InputStream (does not close stream).
     */
    fun sha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return bytesToHex(digest.digest())
    }

    /**
     * Converts raw bytes to lower-case hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val lookup = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = lookup[v ushr 4]
            hexChars[i * 2 + 1] = lookup[v and 0x0F]
        }
        return String(hexChars)
    }
}
