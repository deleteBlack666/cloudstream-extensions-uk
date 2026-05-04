package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*

data class AnimeResultItem(val id: Int, val titleUa: String, val image: ImageInfo?)
data class ImageInfo(val preview: String)

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

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове"
    )

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            if (response.contains("cloudflare", ignoreCase = true) ||
                response.contains("cf-browser-verification", ignoreCase = true) ||
                (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
            ) {
                null
            } else response
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val url = request.data.format(page)
        val jsonText = fetchJsonOrNull(url) ?: return newHomePageResponse(request.name, emptyList())
        val homeList = try {
            val model = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            model.results?.mapNotNull { it?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterApi.format(it.image?.preview ?: "")
                }
            } } ?: emptyList()
        } catch (e: Exception) {
            try {
                val arrayType = object : TypeToken<Array<AnimeResultItem>>() {}.type
                val items: Array<AnimeResultItem> = Gson().fromJson(jsonText, arrayType)
                items.map {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        posterUrl = posterApi.format(it.image?.preview ?: "")
                    }
                }
            } catch (e2: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        val animeJSON = try { Gson().fromJson(jsonText, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        return animeJSON.result?.mapNotNull { it?.let {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                posterUrl = posterApi.format(it.image?.preview ?: "")
                addDubStatus(isDub = true, it.episodes)
            }
        } } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath) ?: throw Exception("Failed to load anime info")
        val animeJSON = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("JSON parse error") }
        val animeId = animeJSON.id ?: throw Exception("Anime ID is null")

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        // СПОСІБ 1: через нові translations (якщо є)
        val translationsUrl = "$mainUrl/api/player/translations/$animeId"
        val translationsJson = fetchJsonOrNull(translationsUrl)
        if (translationsJson != null) {
            try {
                val translationResponse = Gson().fromJson(translationsJson, TranslationResponse::class.java)
                val translation = translationResponse.translations.firstOrNull { it.player.isNotEmpty() }
                if (translation != null) {
                    val player = translation.player.first()
                    val episodesUrl = "$mainUrl/api/player/episodes?playerId=${player.id}&translationId=${translation.id}"
                    val episodesJson = fetchJsonOrNull(episodesUrl)
                    if (episodesJson != null) {
                        val playerJson = Gson().fromJson(episodesJson, PlayerJson::class.java)
                        playerJson.folder.forEach { season ->
                            season.folder.forEachIndexed { idx, ep ->
                                episodes.add(
                                    newEpisode(ep.file) {
                                        name = "${season.title} - Серія ${ep.title}"
                                        posterUrl = ep.poster
                                        episode = idx + 1
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        // СПОСІБ 2: через старі fundubs (якщо перший не спрацював)
        if (episodes.isEmpty()) {
            val fundubsUrl = "$mainUrl/api/player/fundubs/$animeId"
            val fundubsJson = fetchJsonOrNull(fundubsUrl)
            if (fundubsJson != null) {
                try {
                    val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                    val fundubs = fundubsModel.fundubs ?: emptyList()
                    if (fundubs.isNotEmpty()) {
                        val firstFundub = fundubs[0]
                        val firstPlayer = firstFundub.player?.firstOrNull()
                        val fundubId = firstFundub.fundub?.id
                        if (firstPlayer != null && fundubId != null) {
                            val epUrl = "$mainUrl/api/player/episodes/$animeId?playerId=${firstPlayer.id}&fundubId=$fundubId"
                            val epJsonText = fetchJsonOrNull(epUrl)
                            if (epJsonText != null) {
                                val epJson = Gson().fromJson(epJsonText, PlayerEpisodes::class.java)
                                epJson.episodes?.forEach { ep ->
                                    episodes.add(
                                        newEpisode("$animeId, ${ep.episode}") {
                                            name = "Епізод ${ep.episode}"
                                            posterUrl = ep.poster
                                            this.episode = ep.episode
                                            data = "$animeId, ${ep.episode}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        val showStatus = when {
            animeJSON.status?.contains("ongoing") == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val tvType = when {
            animeJSON.type?.contains("tv") == true -> TvType.Anime
            animeJSON.type?.contains("OVA") == true -> TvType.OVA
            animeJSON.type?.contains("ONA") == true -> TvType.OVA
            animeJSON.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(animeJSON.titleUa ?: "Unknown", "$mainUrl/anime/$animeId", tvType) {
            posterUrl = posterApi.format(animeJSON.image?.preview ?: "")
            engName = animeJSON.titleEn
            tags = animeJSON.genres?.mapNotNull { it?.nameUa } ?: emptyList()
            plot = animeJSON.description
            addTrailer(animeJSON.trailer)
            this.showStatus = showStatus
            duration = extractIntFromString(animeJSON.episodeTime ?: "")
            year = animeJSON.releaseDate?.toIntOrNull()
            score = animeJSON.rating?.let { Score.from10(it) }
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeJSON.malId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Якщо data – пряме посилання на відео (з нового способу)
        if (data.startsWith("http")) {
            val m3uUrl = if (data.contains(".m3u8")) data else getM3U(data)
            if (m3uUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8("AnimeON", m3uUrl, mainUrl).dropLast(1).forEach(callback)
                return true
            }
        }

        // Старий спосіб через fundubs
        val dataList = data.split(", ")
        if (dataList.size < 2) return false
        val fundubsUrl = "$mainUrl/api/player/fundubs/${dataList[0]}"
        val fundubsJson = fetchJsonOrNull(fundubsUrl) ?: return false
        val fundubs = try { Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs ?: emptyList() } catch (e: Exception) { return false }
        fundubs.forEach { dub ->
            val player = dub.player?.firstOrNull()
            val fundub = dub.fundub
            if (player != null && fundub != null) {
                val videoUrlResponse = app.get(
                    "${apiUrl}/player/${dataList[0]}/${player.id}/${fundub.id}",
                    headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
                ).parsedSafe<FundubVideoUrl>()?.videoUrl
                if (!videoUrlResponse.isNullOrEmpty()) {
                    val m3uUrl = getM3U(videoUrlResponse)
                    if (m3uUrl.isNotEmpty()) {
                        M3u8Helper.generateM3u8("${fundub.name} (${player.name})", m3uUrl, mainUrl).dropLast(1).forEach(callback)
                    }
                }
            }
        }
        return true
    }

    private fun extractIntFromString(string: String): Int? = Regex("(\\d+)").find(string)?.value?.toIntOrNull()

    private suspend fun getM3U(url: String): String {
        val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
        val html = response.document.select("script").html()
        return if (html.contains("cloudflare", ignoreCase = true)) "" else fileRegex.find(html)?.groups?.get(1)?.value ?: ""
    }
}
