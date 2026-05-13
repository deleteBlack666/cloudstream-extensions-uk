package com.lagradost

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) null
            else response
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull("$searchApi${query}") ?: return emptyList()
        return try {
            val response = Gson().fromJson(jsonText, SearchApiResponse::class.java)
            response?.results?.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                }
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.split("/").last { it.isNotEmpty() }.filter { it.isDigit() }.toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val episodeList = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        
        if (translationsJson != null) {
            val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
            for (item in translations) {
                val dubName = item.translation.name // Назва озвучки (напр. FanVoxUA)
                for (player in item.player) {
                    val epUrl = "$mainUrl/api/player/$animeId/episodes?take=500&skip=-1&playerId=${player.id}&translationId=${item.translation.id}"
                    val epJson = fetchJsonOrNull(epUrl) ?: continue
                    val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java)?.episodes ?: continue

                    for (ep in eps) {
                        episodeList.add(newEpisode("$animeId|${ep.id}") {
                            this.name = "Епізод ${ep.episode} ($dubName)"
                            this.episode = ep.episode
                            this.posterUrl = ep.poster.takeIf { it.isNotEmpty() }
                        })
                    }
                }
            }
        }

        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", TvType.Anime) {
            this.posterUrl = posterApi.format(animeJSON.image.preview)
            this.plot = animeJSON.description
            this.year = animeJSON.releaseDate.toIntOrNull()
            addEpisodes(DubStatus.Dubbed, episodeList.sortedBy { it.episode })
            addMalId(animeJSON.malId.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val epId = parts.last().toIntOrNull() ?: return false
        
        // Прямий запит для отримання посилання на конкретний вибраний епізод
        val epDetailJson = fetchJsonOrNull("$mainUrl/api/player/$epId/episode") ?: return false
        val epData = Gson().fromJson(epDetailJson, FundubEpisode::class.java) ?: return false

        // 1. Якщо є fileUrl (прямий m3u8)
        if (!epData.fileUrl.isNullOrEmpty()) {
            extractM3u8(epData.fileUrl!!, "AnimeON Direct", "https://ashdi.vip/", callback)
            return true
        }

        // 2. Якщо є videoUrl (сторінка плеєра)
        if (!epData.videoUrl.isNullOrEmpty()) {
            val vUrl = epData.videoUrl!!
            when {
                vUrl.contains("ashdi.vip") -> processAshdiIframe(vUrl, callback)
                vUrl.contains("moonanime") -> {
                    val moonFile = getMoonFile(vUrl)
                    if (moonFile.isNotEmpty()) extractM3u8(moonFile, "MoonAnime", "https://moonanime.art/", callback)
                }
                vUrl.contains(".m3u8") -> extractM3u8(vUrl, "AnimeON VOD", mainUrl, callback)
            }
        }
        return true
    }

    private suspend fun extractM3u8(url: String, name: String, referer: String, callback: (ExtractorLink) -> Unit) {
        M3u8Helper.generateM3u8(name, url, referer, headers = mapOf("User-Agent" to userAgent)).forEach(callback)
    }

    private suspend fun processAshdiIframe(iframeUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val url = if (iframeUrl.contains("?")) iframeUrl else "$iframeUrl?player=animeon.club"
            val html = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            val fileRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            fileRegex.find(html)?.groupValues?.get(1)?.let { extractM3u8(it, "Ashdi VOD", "https://ashdi.vip/", callback) }
        } catch (e: Exception) { }
    }

    private suspend fun getMoonFile(iframeUrl: String): String {
        val html = app.get(iframeUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
        val fileRegex = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
        fileRegex.find(html)?.groupValues?.get(1)?.let { return moonDecrypt(it) }
        return ""
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val result = StringBuilder()
            for (i in decoded.indices) result.append((decoded[i].toInt() and 0xFF xor key[i % key.length].code).toChar())
            result.toString()
        } catch (e: Exception) { "" }
    }

    // Допоміжні класи для JSON
    data class SearchApiResponse(val results: List<Result>?)
    data class Result(val id: Int, val titleUa: String, val image: ImageModel, val episodes: Int)
    data class ImageModel(val preview: String)
}
