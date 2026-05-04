package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text
            if (response.contains("cloudflare", ignoreCase = true) ||
                response.contains("cf-browser-verification", ignoreCase = true) ||
                (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
            ) null else response
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())

        return if (request.data.contains("pageIndex")) {
            val parsedJSON = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            val homeList = parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            }
            newHomePageResponse(request.name, homeList)
        } else {
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            val homeList = parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            }
            newHomePageResponse(request.name, homeList)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        val animeJSON = try {
            Gson().fromJson(jsonText, SearchModel::class.java)
        } catch (e: Exception) {
            return emptyList()
        }

        return animeJSON.result.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image.preview)
                addDubStatus(isDub = true, it.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val jsonText = fetchJsonOrNull(url.replace("/anime/", "/api/anime/"))
            ?: throw Exception("Failed to load anime info")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)
        val animeId = animeJSON.id

        val showStatus = when {
            animeJSON.status.contains("ongoing") -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val tvType = with(animeJSON.type!!) {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") -> TvType.OVA
                contains("Спеціальний випуск") -> TvType.OVA
                contains("ONA") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val episodes = mutableListOf<Episode>()

        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId")
        if (fundubsJson != null) {
            try {
                val fundubs = Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs
                if (fundubs.isNotEmpty()) {
                    val firstFundub = fundubs[0]
                    val playerId = firstFundub.player.firstOrNull()?.id
                    val translationId = firstFundub.fundub.id

                    if (playerId != null) {
                        // Новий ендпоінт
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=$playerId&translationId=$translationId"
                        val epJson = fetchJsonOrNull(epUrl)
                        if (epJson != null) {
                            try {
                                Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes?.forEach { ep ->
                                    episodes.add(
                                        newEpisode("$animeId, ${ep.episode}") {
                                            this.name = "Епізод ${ep.episode}"
                                            this.posterUrl = ep.poster
                                            this.episode = ep.episode
                                            this.data = "$animeId, ${ep.episode}"
                                        }
                                    )
                                }
                            } catch (e: Exception) {
                                // Ігноруємо
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ігноруємо
            }
        }

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(
                animeJSON.titleUa,
                "$mainUrl/anime/$animeId",
                tvType,
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
        } else {
            var backgroundImage = animeJSON.backgroundImage
            if (backgroundImage.isNullOrBlank()) {
                backgroundImage = posterApi.format(animeJSON.image.preview)
            } else {
                backgroundImage = posterApi.format(animeJSON.screenshots.first().original)
            }
            newMovieLoadResponse(
                animeJSON.titleUa,
                "$mainUrl/anime/$animeId",
                tvType,
                "$animeId"
            ) {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)
                addMalId(animeJSON.malId.toIntOrNull())
            }
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

        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/${dataList[0]}") ?: return false
        val fundubs = try {
            Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs
        } catch (e: Exception) {
            return false
        }

        fundubs.forEach { dub ->
            val player = dub.player.firstOrNull() ?: return@forEach
            val translationId = dub.fundub.id

            // Знайти конкретний епізод
            val epUrl = "$mainUrl/api/player/${dataList[0]}/episodes?take=100&skip=-1&playerId=${player.id}&translationId=$translationId"
            val epJson = fetchJsonOrNull(epUrl) ?: return@forEach

            val episode = try {
                Gson().fromJson(epJson, PlayerEpisodes::class.java)
                    .episodes?.firstOrNull { it.episode == dataList[1].toIntOrNull() }
            } catch (e: Exception) {
                null
            } ?: return@forEach

            val videoUrl = try {
                app.get(
                    "$mainUrl/api/player/${episode.id}/episode",
                    headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
                ).parsedSafe<FundubVideoUrl>()?.videoUrl
            } catch (e: Exception) {
                null
            } ?: return@forEach

            val m3uUrl = getM3U(videoUrl)
            if (m3uUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8(
                    source = "${dub.fundub.name} (${player.name})",
                    streamUrl = m3uUrl,
                    referer = "https://moonanime.art/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
                        "Accept" to "*/*",
                        "accept-language" to "uk,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                        "origin" to "https://moonanime.art"
                    )
                ).dropLast(1).forEach(callback)
            }
        }

        return true
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") return value.value.drop(1).toIntOrNull()
        return value.value.toIntOrNull()
    }

    private suspend fun getM3U(url: String): String {
        return try {
            val html = app.get(url, headers = mapOf(
                "Host" to url.substringAfter("://").substringBefore("/"),
                "Accept" to "*/*",
                "User-Agent" to userAgent,
                "accept-language" to "en-US,en;q=0.5"
            )).document.select("script").html()
            if (html.contains("cloudflare", ignoreCase = true)) ""
            else fileRegex.find(html)?.groups?.get(1)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
