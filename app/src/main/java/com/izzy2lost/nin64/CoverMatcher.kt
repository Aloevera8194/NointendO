package com.izzy2lost.nin64

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Caches the cover-art index from the GitHub repo (one .webp filename per line) and
 * fuzzy-matches a ROM filename to a cover. The index is downloaded once per day,
 * while resolved images are stored in app storage for offline reuse.
 *
 * Match order:
 *   1. Exact normalized match (strip region/version tags, lowercase, alphanumeric only)
 *   2. Prefix/substring (handles subtitles added or removed)
 *   3. Greedy LCS similarity ≥ 0.75 (catches minor spelling differences)
 */
internal object CoverMatcher {

    private const val PREFS = "cover_index"
    private const val KEY_DATA = "data_webp"
    private const val KEY_TS = "ts_webp"
    private const val TTL_MS = 24L * 3600_000
    private const val COVER_CACHE_DIR = "cover_art"
    private const val COVER_EXTENSION = "webp"
    private const val KEY_NOT_FOUND_PREFIX = "not_found_"
    private const val NOT_FOUND_RETRY_MS = 7L * 24 * 3600_000

    @Volatile private var normToFile: Map<String, String> = emptyMap()
    @Volatile private var ready = false
    private const val LR_PREFIX = "lr:"
    private const val LR_BASE_URL =
        "https://thumbnails.libretro.com/Nintendo%20-%20Nintendo%2064/Named_Boxarts"
    private const val KEY_DATA_LR = "data_libretro"
    private const val KEY_TS_LR = "ts_libretro"
    @Volatile private var extraNormToFile: Map<String, String> = emptyMap()
    @Volatile private var extraInFlight = false
    @Volatile private var indexRefreshInFlight = false
    private val inFlightDownloads = mutableSetOf<String>()
    private val resolutionCache = mutableMapOf<String, String?>()
    private val coverDownloadExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "Nin64-CoverCache").apply { isDaemon = true }
    }

    fun init(context: Context, indexUrl: String, onReady: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DATA, null)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TS, 0)

        if (ready) {
            onReady?.invoke()
            if (cached == null || age > TTL_MS) {
                refreshIndexAsync(appContext, indexUrl, onReady)
            }
            return
        }

        if (cached != null) {
            Thread {
                updateIndex(cached)
                onReady?.invoke()
                if (age > TTL_MS) {
                    refreshIndex(appContext, indexUrl, onReady)
                }
            }.apply {
                name = "Nin64-CoverIndex"
                start()
            }
        } else {
            refreshIndexAsync(appContext, indexUrl, onReady)
        }
    }

    /** Second cover source: the libretro thumbnails server (has Japanese titles). */
    fun initExtra(context: Context, onReady: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DATA_LR, null)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TS_LR, 0)
        synchronized(this) {
            if (extraInFlight) return
            extraInFlight = true
        }
        Thread {
            try {
                if (cached != null && extraNormToFile.isEmpty()) {
                    applyExtra(cached)
                    onReady?.invoke()
                }
                if (cached == null || age > TTL_MS) {
                    val html = URL("$LR_BASE_URL/").readText()
                    val names = Regex("href=\"([^\"]+\\.png)\"")
                        .findAll(html)
                        .map { Uri.decode(it.groupValues[1]) }
                        .filter { !it.startsWith("/") }
                        .joinToString("\n")
                    if (names.isNotBlank()) {
                        prefs.edit()
                            .putString(KEY_DATA_LR, names)
                            .putLong(KEY_TS_LR, System.currentTimeMillis())
                            .apply()
                        applyExtra(names)
                        onReady?.invoke()
                    }
                }
            } catch (_: Exception) {
            } finally {
                extraInFlight = false
            }
        }.apply {
            name = "Nin64-CoverIndexLR"
            start()
        }
    }

    private fun applyExtra(text: String) {
        extraNormToFile = text.lines()
            .filter { it.endsWith(".png") }
            .sortedBy { if ("(USA" in it || "(World" in it) 1 else 0 }
            .associateBy { normalize(stripKnownExtension(it)) }
        synchronized(resolutionCache) {
            resolutionCache.clear()
        }
    }

    private fun resolveExtra(normNames: List<String>): String? {
        val index = extraNormToFile
        if (index.isEmpty()) return null

        for (n in normNames) {
            index[n]?.let { return LR_PREFIX + it }
        }
        for (n in normNames) {
            if (n.length < 5) continue
            val hits = index.entries.filter { (k, _) ->
                k.startsWith(n) || (k.length >= 5 && n.startsWith(k))
            }
            if (hits.isNotEmpty()) {
                return LR_PREFIX +
                    hits.minBy { (k, _) -> kotlin.math.abs(k.length - n.length) }.value
            }
        }
        return normNames
            .asSequence()
            .flatMap { n -> index.entries.asSequence().map { (k, v) -> v to similarity(n, k) } }
            .filter { (_, score) -> score >= 0.85f }
            .maxByOrNull { (_, s) -> s }
            ?.first
            ?.let { LR_PREFIX + it }
    }

    fun isReady(): Boolean = ready

    private fun refreshIndexAsync(context: Context, indexUrl: String, onReady: (() -> Unit)?) {
        synchronized(this) {
            if (indexRefreshInFlight) return
            indexRefreshInFlight = true
        }

        Thread {
            try {
                refreshIndex(context, indexUrl, onReady)
            } finally {
                indexRefreshInFlight = false
            }
        }.apply {
            name = "Nin64-CoverIndex"
            start()
        }
    }

    private fun refreshIndex(context: Context, indexUrl: String, onReady: (() -> Unit)?) {
        try {
            val text = URL(indexUrl).readText()
            if (text.isNotBlank()) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DATA, text)
                    .putLong(KEY_TS, System.currentTimeMillis())
                    .apply()
                updateIndex(text)
                onReady?.invoke()
            }
        } catch (_: Exception) {
        }
    }

    private fun updateIndex(text: String) {
        normToFile = buildIndex(text)
        synchronized(resolutionCache) {
            resolutionCache.clear()
        }
        ready = true
    }

    /** Best-matching cover filename for the ROM, or null if none. */
    fun resolve(romFileName: String): String? {
        return resolve(listOf(romFileName))
    }

    /** Best-matching cover filename for any known ROM name, or null if none. */
    fun resolve(names: Iterable<String?>): String? {
        if (!ready) return null
        val normNames = names
            .mapNotNull { name -> name?.let(::stripKnownExtension)?.let(::normalize) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normNames.isEmpty()) return null

        val cacheKey = normNames.joinToString(separator = "\u0001")
        synchronized(resolutionCache) {
            if (resolutionCache.containsKey(cacheKey)) {
                return resolutionCache[cacheKey]
            }
        }

        val resolved = resolveNormalized(normNames) ?: resolveExtra(normNames)
        synchronized(resolutionCache) {
            resolutionCache[cacheKey] = resolved
        }
        return resolved
    }

    private fun resolveNormalized(normNames: List<String>): String? {
        for (normRom in normNames) {
            normToFile[normRom]?.let { return it }
        }

        for (normRom in normNames) {
            val prefixHits = normToFile.entries.filter { (k, _) ->
                k.startsWith(normRom) || normRom.startsWith(k)
            }
            if (prefixHits.isNotEmpty()) {
                return prefixHits.minBy { (k, _) -> kotlin.math.abs(k.length - normRom.length) }.value
            }
        }

        return normNames
            .asSequence()
            .flatMap { normRom ->
                normToFile.entries.asSequence().map { (k, v) -> v to similarity(normRom, k) }
            }
            .filter { (_, score) -> score >= 0.75f }
            .maxByOrNull { (_, s) -> s }
            ?.first
    }

    fun cachedCoverOrQueueDownload(
        context: Context,
        coverFileName: String,
        baseUrl: String,
        onCached: (() -> Unit)? = null,
    ): File? {
        val appContext = context.applicationContext
        val cachedFile = cachedCoverFile(appContext, coverFileName)
        if (cachedFile != null) return cachedFile

        queueCoverDownload(appContext, coverFileName, baseUrl, onCached)
        return null
    }

    fun prefetchCovers(
        context: Context,
        coverFileNames: Iterable<String>,
        baseUrl: String,
        onCached: (() -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        coverFileNames
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { coverFileName ->
                if (cachedCoverFile(appContext, coverFileName) == null) {
                    queueCoverDownload(appContext, coverFileName, baseUrl, onCached)
                }
            }
    }

    fun cachedCoverFile(context: Context, coverFileName: String): File? {
        val file = coverFileFor(context, coverFileName)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun queueCoverDownload(
        context: Context,
        coverFileName: String,
        baseUrl: String,
        onCached: (() -> Unit)?,
    ) {
        if (wasRecentlyNotFound(context, coverFileName)) return

        synchronized(inFlightDownloads) {
            if (!inFlightDownloads.add(coverFileName)) return
        }

        coverDownloadExecutor.execute {
            try {
                val target = coverFileFor(context, coverFileName)
                if (!(target.isFile && target.length() > 0L)) {
                    target.parentFile?.mkdirs()
                    val temp = File.createTempFile("cover-", ".tmp", target.parentFile)
                    try {
                        val connection = URL(remoteCoverUrl(baseUrl, coverFileName)).openConnection().apply {
                            connectTimeout = 8_000
                            readTimeout = 15_000
                        }
                        connection.getInputStream().use { input ->
                            temp.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (temp.length() > 0L) {
                            if (target.exists()) target.delete()
                            if (!temp.renameTo(target)) {
                                temp.copyTo(target, overwrite = true)
                                temp.delete()
                            }
                            clearNotFound(context, coverFileName)
                            onCached?.invoke()
                        }
                    } finally {
                        temp.delete()
                    }
                }
            } catch (_: FileNotFoundException) {
                markNotFound(context, coverFileName)
            } catch (_: Exception) {
            } finally {
                synchronized(inFlightDownloads) {
                    inFlightDownloads.remove(coverFileName)
                }
            }
        }
    }

    private fun buildIndex(text: String): Map<String, String> =
        text.lines()
            .filter { it.endsWith(".$COVER_EXTENSION") }
            .associateBy { normalize(stripKnownExtension(it)) }

    private fun stripKnownExtension(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.lowercase() in KNOWN_EXTENSIONS) {
            name.substringBeforeLast('.')
        } else {
            name
        }
    }

    private fun normalize(name: String): String =
        name
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        var i = 0; var j = 0; var common = 0
        while (i < a.length && j < b.length) {
            when {
                a[i] == b[j] -> { common++; i++; j++ }
                a.length - i > b.length - j -> i++
                else -> j++
            }
        }
        return 2f * common / (a.length + b.length)
    }

    private fun coverFileFor(context: Context, coverFileName: String): File {
        return File(coverCacheDirectory(context), safeCacheName(coverFileName))
    }

    private fun coverCacheDirectory(context: Context): File =
        File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }

    private fun remoteCoverUrl(baseUrl: String, coverFileName: String): String =
        if (coverFileName.startsWith(LR_PREFIX)) {
            "$LR_BASE_URL/${Uri.encode(coverFileName.removePrefix(LR_PREFIX))}"
        } else {
            "$baseUrl/${Uri.encode(coverFileName)}"
        }

    private fun wasRecentlyNotFound(context: Context, coverFileName: String): Boolean {
        val notFoundAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(notFoundKey(coverFileName), 0L)
        return notFoundAt > 0L && System.currentTimeMillis() - notFoundAt < NOT_FOUND_RETRY_MS
    }

    private fun markNotFound(context: Context, coverFileName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(notFoundKey(coverFileName), System.currentTimeMillis())
            .apply()
    }

    private fun clearNotFound(context: Context, coverFileName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(notFoundKey(coverFileName))
            .apply()
    }

    private fun notFoundKey(coverFileName: String): String =
        "$KEY_NOT_FOUND_PREFIX${safeCacheName(coverFileName)}"

    private fun safeCacheName(coverFileName: String): String {
        val safeName = coverFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return safeName.ifBlank { "cover.$COVER_EXTENSION" }
    }

    private val KNOWN_EXTENSIONS = setOf("z64", "n64", "v64", "rom", "bin", "zip", "png", "webp")
}
