package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/116"

    private fun safeJson(url: String): String? {
        return try {
            val r = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).text
            if (r.startsWith("{") || r.startsWith("[")) r else null
        } catch (_: Exception) {
            null
        }
    }

    // --------------------------
    // LOAD LINKS (FIXED)
    // --------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split(", ")
        if (parts.size < 2) return false

        val animeId = parts[0]
        val episodeNum = parts[1].toIntOrNull() ?: return false
        val episodeId = parts.getOrNull(2)?.toIntOrNull()

        val translationsJson =
            safeJson("$mainUrl/api/player/$animeId/translations") ?: return false

        val translations = try {
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
        } catch (_: Exception) {
            return false
        }

        var foundAny = false

        translations.forEach { t ->
            t.player.forEach { player ->

                // --------------------------
                // EPISODE FETCH (safe)
                // --------------------------
                val epJson = safeJson(
                    "$mainUrl/api/player/$animeId/episodes?take=200&skip=0" +
                            "&playerId=${player.id}&translationId=${t.translation.id}"
                ) ?: return@forEach

                val eps = try {
                    Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                } catch (_: Exception) {
                    emptyList()
                }

                val ep = eps.firstOrNull { it.episode == episodeNum } ?: return@forEach

                // ======================================================
                // 1) MOON FIRST (PRIORITY)
                // ======================================================
                val moonUrl = ep.videoUrl

                if (!moonUrl.isNullOrBlank() && moonUrl.contains("moonanime.art")) {

                    val moonStream = runCatching { getMoonFile(moonUrl) }.getOrNull()

                    if (!moonStream.isNullOrBlank()) {

                        if (moonStream.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "Moon",
                                streamUrl = moonStream,
                                referer = "https://moonanime.art/"
                            ).forEach(callback)

                            foundAny = true
                            return@forEach
                        }
                    }
                }

                // ======================================================
                // 2) ASHDI FALLBACK
                // ======================================================
                val fileUrl = ep.fileUrl
                if (!fileUrl.isNullOrBlank() && fileUrl.contains(".m3u8")) {

                    M3u8Helper.generateM3u8(
                        source = "Ashdi",
                        streamUrl = fileUrl,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)

                    foundAny = true
                }
            }
        }

        return foundAny
    }

    // --------------------------
    // MOON DECODE (kept intact)
    // --------------------------
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

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
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
