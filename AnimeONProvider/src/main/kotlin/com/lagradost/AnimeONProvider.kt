package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*
import java.net.URLEncoder

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
    private val searchApi = "$apiUrl/search?text="

    private val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове"
    )

    // Кеш cookies
    private var cachedCookies: String? = null

    private suspend fun getCookies(): String? {
        if (cachedCookies != null) return cachedCookies
        val response = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent))
        val cookies = response.cookies()?.joinToString("; ") { "${it.name}=${it.value}" }
        cachedCookies = cookies
        return cookies
    }

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val headers = mutableMapOf("Referer" to mainUrl, "User-Agent" to userAgent)
            getCookies()?.let { headers["Cookie"] = it }
            val response = app.get(url, headers = headers)
            val text = response.text
            if (text.contains("cloudflare", ignoreCase = true) ||
                text.contains("cf-browser-verification", ignoreCase = true) ||
                (!text.trimStart().startsWith("{") && !text.trimStart().startsWith("["))
            ) null else text
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())
        val homeList = try {
            val model = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            model.results?.mapNotNull { result ->
                result?.let {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        posterUrl = posterApi.format(it.image.preview)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            try {
                val arrayType = object : TypeToken<Array<AnimeModel>>() {}.type
                val items: Array<AnimeModel> = Gson().fromJson(jsonText, arrayType)
                items.map { item ->
                    newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                        posterUrl = posterApi.format(item.image.preview)
                    }
                }
            } catch (e2: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        val searchModel = try { Gson().fromJson(jsonText, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        return searchModel.result?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath) ?: throw Exception("Не вдалося завантажити інформацію про аніме")
        val animeInfo = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("Помилка парсингу JSON") }
        val animeId = animeInfo.id

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId")
        if (fundubsJson != null) {
            try {
                val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                val fundubs = fundubsModel.fundubs ?: emptyList()
                if (fundubs.isNotEmpty()) {
                    val fundub = fundubs[0]
                    val player = fundub.player.firstOrNull()
                    val fundubId = fundub.fundub.id
                    if (player != null) {
                        val episodesUrl = "$mainUrl/api/player/episodes/$animeId?playerId=${player.id}&fundubId=$fundubId"
                        val episodesJson = fetchJsonOrNull(episodesUrl)
                        if (episodesJson != null) {
                            val playerEpisodes = Gson().fromJson(episodesJson, PlayerEpisodes::class.java)
                            playerEpisodes.episodes?.forEach { ep ->
                                episodes.add(
                                    newEpisode("$animeId,${ep.episode}") {
                                        name = "Епізод ${ep.episode}"
                                        posterUrl = ep.poster
                                        this.episode = ep.episode
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        val showStatus = if (animeInfo.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = when {
            animeInfo.type?.contains("tv") == true -> TvType.Anime
            animeInfo.type?.contains("OVA") == true -> TvType.OVA
            animeInfo.type?.contains("ONA") == true -> TvType.OVA
            animeInfo.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(animeInfo.titleUa, "$mainUrl/anime/$animeId", tvType) {
            posterUrl = posterApi.format(animeInfo.image.preview)
            engName = animeInfo.titleEn
            tags = animeInfo.genres?.map { it.nameUa } ?: emptyList()
            plot = animeInfo.description
            addTrailer(animeInfo.trailer)
            this.showStatus = showStatus
            year = animeInfo.releaseDate.toIntOrNull()
            score = animeInfo.rating?.toDoubleOrNull()?.let { Score.from10(it) }
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeInfo.malId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(",")
        if (dataList.size < 2) return false
        val animeId = dataList[0].trim().toIntOrNull() ?: return false
        val episodeNum = dataList[1].trim().toIntOrNull() ?: return false

        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId") ?: return false
        val fundubsModel = try { Gson().fromJson(fundubsJson, FundubsModel::class.java) } catch (e: Exception) { return false }
        val fundub = fundubsModel.fundubs?.firstOrNull() ?: return false
        val player = fundub.player.firstOrNull() ?: return false
        val fundubId = fundub.fundub.id

        val videoInfoUrl = "$apiUrl/player/$animeId/${player.id}/$fundubId?episode=$episodeNum"
        val videoInfoJson = fetchJsonOrNull(videoInfoUrl) ?: return false
        val videoUrlData = try { Gson().fromJson(videoInfoJson, FundubVideoUrl::class.java) } catch (e: Exception) { return false }
        val videoUrl = videoUrlData.videoUrl
        if (videoUrl.isNotEmpty()) {
            val finalUrl = if (videoUrl.contains(".m3u8")) videoUrl else getM3U8FromPage(videoUrl)
            if (finalUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8("AnimeON", finalUrl, referer = mainUrl).dropLast(1).forEach(callback)
                return true
            }
        }
        return false
    }

    private suspend fun getM3U8FromPage(url: String): String {
        val headers = mutableMapOf("Referer" to mainUrl, "User-Agent" to userAgent)
        getCookies()?.let { headers["Cookie"] = it }
        val response = app.get(url, headers = headers)
        val html = response.document.select("script").html()
        return fileRegex.find(html)?.groupValues?.get(1) ?: ""
    }
}
