package com.lagradost

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.models.*

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
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private data class SearchApiResponse(
        @SerializedName("results") val results: List<Result>?,
        @SerializedName("totalCount") val totalCount: Int? = null,
    )

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text
            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) null
            else response
        } catch (e: Exception) { null }
    }

    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty() || !videoUrl.contains("ashdi.vip")) return null
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        return try {
            val html = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
            val posterRegex = Regex("""poster:\s*["'](https?://[^"']+)["']""")
            val match = posterRegex.find(html)?.groupValues?.get(1) ?: return null
            "https://" + match.removePrefix("http://").removePrefix("https://")
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false") ?: return newHomePageResponse(request.name, emptyList())
            val parsedJSON = try { Gson().fromJson<List<Results>>(jsonText, listResults) } catch(e: Exception) { null } ?: emptyList()
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }

        val jsonText = fetchJsonOrNull(if (request.data.contains("%d")) request.data.format(page) else request.data) ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = try { Gson().fromJson(jsonText, NewAnimeModel::class.java) } catch(e: Exception) { null }
            newHomePageResponse(request.name, parsedJSON?.results?.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            } ?: emptyList())
        } else {
            val parsedJSON = try { Gson().fromJson<List<Results>>(jsonText, listResults) } catch(e: Exception) { null } ?: emptyList()
            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val id = query.toIntOrNull()
        if (id != null) searchById(id)?.let { return listOf(it) }
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

    private suspend fun searchById(id: Int): SearchResponse? {
        val jsonText = fetchJsonOrNull("$apiUrl/$id") ?: return null
        val anime = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { null } ?: return null
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = posterApi.format(anime.image.preview)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.split("/").last { it.isNotEmpty() }.filter { it.isDigit() }.toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                for (translation in translations) {
                    val dubName = translation.translation.name
                    for (player in translation.player) {
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=500&skip=-1&playerId=${player.id}&translationId=${translation.translation.id}"
                        val epJson = fetchJsonOrNull(epUrl) ?: continue
                        val eps = try { Gson().fromJson(epJson, PlayerEpisodes::class.java)?.episodes } catch (e: Exception) { null } ?: continue

                        for (ep in eps) {
                            val poster = if (ep.poster.isNotEmpty()) ep.poster else getAshdiPoster(ep.videoUrl)
                            episodes.add(newEpisode("$animeId|${ep.id}") {
                                this.name = "Епізод ${ep.episode} ($dubName)"
                                this.posterUrl = poster
                                this.episode = ep.episode
                            })
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", TvType.Anime) {
            this.posterUrl = posterApi.format(animeJSON.image.preview)
            this.plot = animeJSON.description
            this.year = animeJSON.releaseDate.toIntOrNull()
            addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode })
            addMalId(animeJSON.malId.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = data.split("|").last().toIntOrNull() ?: return false
        val epDetailJson = fetchJsonOrNull("$mainUrl/api/player/$epId/episode") ?: return false
        val epData = try { Gson().fromJson(epDetailJson, FundubEpisode::class.java) } catch (e: Exception) { null } ?: return false

        if (!epData.fileUrl.isNullOrEmpty()) {
            extractM3u8(epData.fileUrl!!, "AnimeON Direct", "https://ashdi.vip/", callback)
            return true
        }

        if (!epData.videoUrl.isNullOrEmpty()) {
            val vUrl = epData.videoUrl!!
            when {
                vUrl.contains("moonanime") -> {
                    val moonFile = getMoonFile(vUrl)
                    if (moonFile.isNotEmpty()) extractM3u8(moonFile, "MoonAnime", "https://moonanime.art/", callback)
                }
                vUrl.contains("ashdi.vip") -> processAshdiIframe(vUrl, callback)
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

    // ВИПРАВЛЕНО: Розширене отримання MoonAnime з подвійним дешифруванням
    private suspend fun getMoonFile(iframeUrl: String): String {
        return try {
            val html = app.get(iframeUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)).text
            val fileRegex = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
            val directMatch = fileRegex.find(html)?.groupValues?.get(1)
            
            if (directMatch != null) moonDecrypt(directMatch)
            else {
                val atobRegex = Regex("""atob\(["']([^"']+)["']\)""")
                val atobMatch = atobRegex.find(html)?.groupValues?.get(1)
                if (atobMatch != null) {
                    val decodedJs = moonOuterDecode(atobMatch)
                    fileRegex.find(decodedJs)?.groupValues?.get(1)?.let { moonDecrypt(it) } ?: ""
                } else ""
            }
        } catch (e: Exception) { "" }
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val result = StringBuilder()
            for (i in decoded.indices) result.append((decoded[i].toInt() and 0xFF xor key[i % key.length].code).toChar())
            result.toString()
        } catch (e: Exception) { "" }
    }

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 32) return ""
            val key = raw.sliceArray(0 until 32)
            val data = raw.sliceArray(32 until raw.size)
            val result = StringBuilder()
            for (i in data.indices) result.append(((data[i].toInt() and 0xFF) xor (key[i % 32].toInt() and 0xFF)).toChar())
            result.toString()
        } catch (e: Exception) { "" }
    }

    private fun extractIntFromString(string: String): Int? = Regex("(\\d+)").findAll(string).lastOrNull()?.value?.removePrefix("0")?.toIntOrNull()
}
