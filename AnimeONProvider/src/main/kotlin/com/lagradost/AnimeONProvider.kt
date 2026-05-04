package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
    )

    // Безпечне отримання JSON, повертає null при помилці або Cloudflare
    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text
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
        if (!request.data.contains("pageIndex") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val url = request.data.format(page)
        val jsonText = fetchJsonOrNull(url) ?: return newHomePageResponse(request.name, emptyList())
        
        val parsedJSON = try {
            Gson().fromJson(jsonText, NewAnimeModel::class.java)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val homeList = parsedJSON.results?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image?.preview ?: "")
                }
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = searchApi + query
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()
        val animeJSON = try {
            Gson().fromJson(jsonText, SearchModel::class.java)
        } catch (e: Exception) {
            return emptyList()
        }

        return animeJSON.result?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image?.preview ?: "")
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath) ?: throw Exception("Failed to load anime info")
        val animeJSON = try {
            Gson().fromJson(jsonText, AnimeInfoModel::class.java)
        } catch (e: Exception) {
            throw Exception("JSON parse error")
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

        // Явно вказуємо тип Episode з cloudstream3, щоб уникнути конфлікту
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        val fundubsUrl = "$mainUrl/api/player/fundubs/${animeJSON.id}"
        val fundubsJson = fetchJsonOrNull(fundubsUrl) ?: return newAnimeLoadResponse(
            animeJSON.titleUa, "$mainUrl/anime/${animeJSON.id}", tvType
        ).apply { addEpisodes(DubStatus.Dubbed, emptyList()) }

        val fundubs = try {
            Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs
        } catch (e: Exception) {
            emptyList()
        }

        if (fundubs.isNotEmpty()) {
            val firstPlayer = fundubs.firstOrNull()?.player?.firstOrNull()
            val fundubId = fundubs.firstOrNull()?.fundub?.id
            if (firstPlayer != null && fundubId != null) {
                val epUrl = "$mainUrl/api/player/episodes/${animeJSON.id}?playerId=${firstPlayer.id}&fundubId=$fundubId"
                val epJsonText = fetchJsonOrNull(epUrl)
                if (epJsonText != null) {
                    val epJson = try {
                        Gson().fromJson(epJsonText, PlayerEpisodes::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    epJson?.episodes?.forEach { ep ->
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
            }
        }

        return newAnimeLoadResponse(
            animeJSON.titleUa,
            "$mainUrl/anime/${animeJSON.id}",
            tvType
        ) {
            this.posterUrl = posterApi.format(animeJSON.image?.preview ?: "")
            this.engName = animeJSON.titleEn
            this.tags = animeJSON.genres?.mapNotNull { it?.nameUa } ?: emptyList()
            this.plot = animeJSON.description
            addTrailer(animeJSON.trailer)           // тепер доступно
            this.showStatus = showStatus
            this.duration = extractIntFromString(animeJSON.episodeTime ?: "")
            this.year = animeJSON.releaseDate?.toIntOrNull()
            this.score = Score.from10(animeJSON.rating)
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeJSON.malId?.toIntOrNull()) // тепер доступно
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(", ")
        if (dataList.size < 2) return false
        val fundubsUrl = "$mainUrl/api/player/fundubs/${dataList[0]}"
        val fundubsJson = fetchJsonOrNull(fundubsUrl) ?: return false
        val fundubs = try {
            Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs
        } catch (e: Exception) {
            return false
        }

        fundubs.forEach { dub ->
            val player = dub.player.firstOrNull()
            val fundub = dub.fundub
            if (player != null && fundub != null) {
                val videoUrlResponse = app.get(
                    "${apiUrl}/player/${dataList[0]}/${player.id}/${fundub.id}",
                    headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
                ).parsedSafe<FundubVideoUrl>()?.videoUrl
                if (!videoUrlResponse.isNullOrEmpty()) {
                    val m3uUrl = getM3U(videoUrlResponse)
                    if (m3uUrl.isNotEmpty()) {
                        M3u8Helper.generateM3u8(
                            source = "${fundub.name} (${player.name})",
                            streamUrl = m3uUrl,
                            referer = mainUrl
                        ).dropLast(1).forEach(callback)
                    }
                }
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
