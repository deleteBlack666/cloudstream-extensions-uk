package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.google.gson.JsonElement
import com.google.gson.JsonObject

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override var lang = "uk"
    override val hasMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/116"

    private fun getJson(url: String): JsonElement? {
        return try {
            app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).parsedSafe<JsonElement>()
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------
    // LOAD LINKS (FIXED)
    // -------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split(", ")
        if (parts.size < 2) return false

        val animeId = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false

        val translations = getJson("$mainUrl/api/player/$animeId/translations") ?: return false

        val arr = translations.asJsonObject["translations"]?.asJsonArray ?: return false

        var success = false

        for (t in arr) {
            val obj = t.asJsonObject
            val translationId = obj["translation"]?.asJsonObject?.get("id")?.asInt ?: continue

            val players = obj["player"]?.asJsonArray ?: continue

            for (p in players) {
                val playerId = p.asJsonObject["id"]?.asInt ?: continue

                val epsJson = getJson(
                    "$mainUrl/api/player/$animeId/episodes" +
                            "?take=200&skip=0&playerId=$playerId&translationId=$translationId"
                ) ?: continue

                val epsArr = epsJson.asJsonObject["episodes"]?.asJsonArray ?: continue

                val ep = epsArr.firstOrNull {
                    it.asJsonObject["episode"]?.asInt == epNum
                } ?: continue

                val epObj = ep.asJsonObject

                // =========================
                // MOON FIRST
                // =========================
                val moonUrl = epObj["videoUrl"]?.asString

                if (!moonUrl.isNullOrBlank() && moonUrl.contains("moonanime.art")) {

                    val moonStream = runCatching {
                        getMoonFile(moonUrl)
                    }.getOrNull()

                    if (!moonStream.isNullOrBlank() && moonStream.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = "Moon",
                            streamUrl = moonStream,
                            referer = "https://moonanime.art/"
                        ).forEach(callback)

                        success = true
                        continue
                    }
                }

                // =========================
                // ASHDI FALLBACK
                // =========================
                val fileUrl = epObj["fileUrl"]?.asString

                if (!fileUrl.isNullOrBlank() && fileUrl.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = "Ashdi",
                        streamUrl = fileUrl,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)

                    success = true
                }
            }
        }

        return success
    }

    // -------------------------
    // MOON DECODER (UNCHANGED)
    // -------------------------
    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val out = StringBuilder()

            for (i in decoded.indices) {
                out.append(
                    (decoded[i].toInt() and 0xFF xor key[i % key.length].code).toChar()
                )
            }
            out.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun moonOuterDecode(blob: String): String {
        return try {
            val raw = android.util.Base64.decode(blob, android.util.Base64.DEFAULT)
            if (raw.size < 32) return ""

            val key = raw.sliceArray(0 until 32)
            val data = raw.sliceArray(32 until raw.size)

            val out = StringBuilder()

            for (i in data.indices) {
                out.append(
                    ((data[i].toInt() and 0xFF) xor (key[i % 32].toInt() and 0xFF)).toChar()
                )
            }
            out.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun getMoonFile(url: String): String {
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://animeon.club/"
            )
        ).text

        val fileRegex = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
        val atobRegex = Regex("""atob\(["']([^"']+)["']\)""")

        fileRegex.find(html)?.groupValues?.getOrNull(1)?.let {
            val decoded = moonDecrypt(it)
            if (decoded.isNotBlank()) return decoded
        }

        val atob = atobRegex.find(html)?.groupValues?.getOrNull(1) ?: return ""
        val decodedJs = moonOuterDecode(atob)

        return fileRegex.find(decodedJs)?.groupValues?.getOrNull(1)
            ?.let { moonDecrypt(it) } ?: ""
    }
}
