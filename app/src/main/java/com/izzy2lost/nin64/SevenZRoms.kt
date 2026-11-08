package com.izzy2lost.nin64

import android.content.Context
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

internal object SevenZRoms {
    private const val PART_SUFFIX = ".part"

    fun isSevenZip(name: String?): Boolean =
        name?.lowercase(Locale.ROOT)?.endsWith(".7z") == true

    fun readIdentity(
        context: Context,
        input: InputStream,
        fallbackName: String,
        isRom: (String) -> Boolean,
    ): Pair<String, RomIdentity>? = withArchive(context, input) { archive ->
        var entry = archive.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && isRom(entry.name)) {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = archive.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    bytes.write(buffer, 0, count)
                }
                return@withArchive entry.name to
                    RomIdentityReader.read(ByteArrayInputStream(bytes.toByteArray()), fallbackName)
            }
            entry = archive.nextEntry
        }
        null
    }

    fun extractTo(
        context: Context,
        input: InputStream,
        entryName: String,
        output: java.io.OutputStream,
    ): Long {
        return output.use { stream ->
            val size = withArchive(context, input) { archive ->
                var entry = archive.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == entryName) {
                        var total = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = archive.read(buffer, 0, buffer.size)
                            if (count <= 0) break
                            stream.write(buffer, 0, count)
                            total += count
                        }
                        return@withArchive total
                    }
                    entry = archive.nextEntry
                }
                0L
            } ?: 0L

            if (size <= 0L) {
                throw IOException("Could not extract $entryName from 7z archive")
            }
            size
        }
    }

    fun unpack(
        context: Context,
        key: String,
        entryName: String,
        open: () -> InputStream?,
    ): File {
        val dir = File(context.cacheDir, "unpacked/${safeName(key)}")
        val target = File(dir, safeName(entryName.substringAfterLast('/').substringAfterLast('\\')))
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        dir.mkdirs()
        val part = File(dir, target.name + PART_SUFFIX)
        val input = open() ?: throw IOException("Could not open 7z archive")
        val found = withArchive(context, input) { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == entryName) {
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = archive.read(buffer, 0, buffer.size)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    return@withArchive true
                }
                entry = archive.nextEntry
            }
            false
        } ?: false

        if (!found || !part.isFile || part.length() == 0L || !part.renameTo(target)) {
            part.delete()
            throw IOException("Could not extract $entryName from 7z archive")
        }
        return target
    }

    private fun <T> withArchive(
        context: Context,
        input: InputStream,
        action: (SevenZFile) -> T,
    ): T? {
        val temp = File.createTempFile("n64-archive-", ".7z", context.cacheDir)
        return try {
            input.use { source ->
                temp.outputStream().use { output -> source.copyTo(output) }
            }
            SevenZFile(temp).use(action)
        } finally {
            temp.delete()
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._() -]"), "_")
            .trim('.', ' ')
            .ifBlank { "game" }
}
