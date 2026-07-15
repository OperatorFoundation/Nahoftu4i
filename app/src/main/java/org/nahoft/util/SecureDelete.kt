package org.nahoft.util

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Overwrite-then-delete for files that may hold sensitive data.
 *
 * Note: on flash storage, overwriting a file's logical blocks does not
 * guarantee the physical cells are erased (wear-leveling). This raises the bar
 * against casual recovery but is not a true unrecoverability guarantee
 * should be coupled with minimizing how long sensitive files exist on disk.
 */
object SecureDelete
{
    private const val OVERWRITE_PASSES = 3
    private const val OVERWRITE_BUFFER_BYTES = 4096

    fun secureDelete(file: File)
    {
        if (!file.exists()) return

        try
        {
            val length = file.length()
            val random = SecureRandom()

            repeat(OVERWRITE_PASSES)
            {
                RandomAccessFile(file, "rws").use { raf ->
                    val buffer = ByteArray(OVERWRITE_BUFFER_BYTES)
                    var remaining = length

                    while (remaining > 0)
                    {
                        val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
                        random.nextBytes(buffer)
                        raf.write(buffer, 0, toWrite)
                        remaining -= toWrite
                    }

                    raf.fd.sync()
                }
            }

            file.delete()
        }
        catch (e: Exception)
        {
            Timber.d("Error securely deleting a file: ${e.printStackTrace()}")
        }
    }
}