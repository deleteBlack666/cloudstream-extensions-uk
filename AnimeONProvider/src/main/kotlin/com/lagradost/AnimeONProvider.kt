package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
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

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$apiUrl/search?text="

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    // Додаємо User-Agent для імітації браузера
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
    )

    // Допоміжна функція для безпечного отримання JSON
    private suspend fun fetchJson(url: String): String {
        val response = app.get(url, headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to userAgent
        )).text
        if (response.contains("cloudflare", ignoreCase = true) ||
            response.contains("cf-browser-verification", ignoreCase = true) ||
            (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
        ) {
            throw Exception("Cloudflare protection detected or invalid JSON from $url")
        }
        return response
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val url = request.data.format(page)
        val jsonText = fetchJson(url)
        val parsedJSON = Gson().fromJson(jsonText, NewAnimeModel::class.java)

        val homeList = parsedJSON.results.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image.preview)
            }
        }

        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = searchApi + query
        val jsonText = fetchJson(url)
        val animeJSON = Gson().fromJson(jsonText, SearchModel::class.java)

        return animeJSON.result.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image.preview)
                addDubStatus(isDub = true, it.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJson(apiUrlPath)
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

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

        val episodes = mutableListOf<Episode>()

        val fundubsUrl = "$mainUrl/api/player/fundubs/${animeJSON.id}"
        val fundubsJson = fetchJson(fundubsUrl)
        val fundubs = Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs

        if (fundubs.isNotEmpty()) {
            val epUrl = "$mainUrl/api/player/episodes/${animeJSON.id}?playerId=${fundubs[0].player[0].id}&fundubId=${fundubs[0].fundub.id}"
            val epJsonText = fetchJson(epUrl)
            val epJson = Gson().fromJson(epJsonText, PlayerEpisodes::class.java)

            epJson.episodes.forEach { ep ->
                episodes.add(
                    newEpisode("${animeJSON.id}, ${ep.episode}") {
                        this.name = "Епізод ${ep.episode}"
                        this.posterUrl = ep.poster
                        this.episode = ep.episode
                        this.data = "${animeJSON.id}, ${ep.episode}"
                    }
                )
            }
        }

        return newAnimeLoadResponse(
            animeJSON.titleUa,
            "$mainUrl/anime/${animeJSON.id}",
            tvType
        ) {
            this.posterUrl = posterApi.format(animeJSON.image.preview)
            this.engName = animeJSON.titleEn
            this.tags = animeJSON.genres.map { it.nameUa }
            this.plot = animeJSON.description
            addTrailer(animeJSON.trailer)
            this.showStatus = showStatus
            this.duration = extractIntFromString(animeJSON.episodeTime)
            this.year = animeJSON.releaseDate.toIntOrNull()
            this.score = Score.from10(animeJSON.rating)
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeJSON.malId.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        val fundubsUrl = "$mainUrl/api/player/fundubs/${dataList[0]}"
        val fundubsJson = try {
            fetchJson(fundubsUrl)
        } catch (e: Exception) {
            return false
        }
        val fundubs = Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs

        fundubs.forEach { dub ->
            val videoUrlResponse = app.get(
                "${apiUrl}/player/${dataList[0]}/${dub.player[0].id}/${dub.fundub.id}",
                headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
            ).parsedSafe<FundubVideoUrl>()?.videoUrl ?: return@forEach

            val m3uUrl = getM3U(videoUrlResponse)
            if (m3uUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8(
                    source = "${dub.fundub.name} (${dub.player[0].name})",
                    streamUrl = m3uUrl,
                    referer = mainUrl
                ).dropLast(1).forEach(callback)
            }
        }

        return true
    }

    private fun extractIntFromString(string: String): Int? {
        return Regex("(\\d+)").find(string)?.value?.toIntOrNull()
    }

    private suspend fun getM3U(url: String): String {
        val response = app.get(url, headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to userAgent
        ))
        val html = response.document.select("script").html()
        if (html.contains("cloudflare", ignoreCase = true)) return ""
        return fileRegex.find(html)?.groups?.get(1)?.value ?: ""
    }
}
