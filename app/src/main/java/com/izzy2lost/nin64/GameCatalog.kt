package com.izzy2lost.nin64

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

internal data class CatalogGame(
    val id: String,
    val title: String,
    val serials: List<String>,
    val category: String,
    val coverPath: String? = null,
    val manual: Boolean = false
)

internal object GameCatalog {
    private const val TITLES_URL =
        "https://github.com/niemasd/GameDB-N64/releases/latest/download/N64.titles.json"

    private fun officialFile(context: Context) =
        File(context.filesDir, "catalog_official.json")

    private fun manualFile(context: Context) =
        File(context.filesDir, "catalog_manual.json")

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()

    fun load(context: Context): List<CatalogGame> {
        val result = linkedMapOf<String, CatalogGame>()

        fun read(file: File, manual: Boolean) {
            if (!file.exists()) return
            runCatching {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val game = CatalogGame(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        serials = obj.optJSONArray("serials")?.let { a ->
                            (0 until a.length()).map { a.getString(it) }
                        } ?: emptyList(),
                        category = obj.optString("category", "official"),
                        coverPath = obj.optString("coverPath").takeIf { it.isNotBlank() },
                        manual = manual
                    )
                    if (game.title.isNotBlank()) {
                        result[if (manual) "manual:${game.id}" else normalize(game.title)] = game
                    }
                }
            }
        }

        read(officialFile(context), false)
        read(manualFile(context), true)
        return result.values.sortedBy { normalize(it.title) }
    }

    fun update(context: Context): Int {
        val connection = URL(TITLES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"

        try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val source = JSONObject(response)
            val grouped = linkedMapOf<String, MutableList<String>>()
            val names = linkedMapOf<String, String>()

            val keys = source.keys()
            while (keys.hasNext()) {
                val serial = keys.next()
                val title = source.optString(serial).trim()
                if (title.isBlank()) continue

                val key = normalize(title)
                names.putIfAbsent(key, title)
                grouped.getOrPut(key) { mutableListOf() }.add(serial)
            }

            val output = JSONArray()
            for ((key, serials) in grouped) {
                val obj = JSONObject()
                obj.put("id", key.replace(" ", "-"))
                obj.put("title", names[key])
                obj.put("category", "official")
                obj.put("serials", JSONArray(serials.distinct().sorted()))
                output.put(obj)
            }

            val file = officialFile(context)
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(output.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
            return output.length()
        } finally {
            connection.disconnect()
        }
    }

    fun titleForGameCode(context: Context, gameCode: String): String? {
        val code = gameCode.uppercase(Locale.ROOT)
        return load(context).firstOrNull { game ->
            game.serials.any { serial ->
                serial.substringAfter("NUS-", "").substringBefore("-") == code
            }
        }?.title
    }

    fun addManual(
        context: Context,
        title: String,
        coverPath: String? = null,
        category: String = "manual"
    ) {
        require(title.isNotBlank())
        val file = manualFile(context)
        val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        val id = "manual-${System.currentTimeMillis()}"

        val obj = JSONObject()
        obj.put("id", id)
        obj.put("title", title.trim())
        obj.put("category", category)
        obj.put("serials", JSONArray())
        obj.put("coverPath", coverPath ?: "")
        array.put(obj)

        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}
