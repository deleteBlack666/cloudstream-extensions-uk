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
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl?pageSize=24&pageIndex=%d&sort=rating" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.contains("seasons") && page != 1) 
            return newHomePageResponse(request.name, emptyList())

        val jsonText = fetchJsonOrNull(request.data.format(page)) 
            ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } else {
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        return try {
            Gson().fromJson(jsonText, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val showStatus = if (animeJSON.status.contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = with(animeJSON.type) {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val episodes = mutableListOf<Episode>()

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                if (translations.isNotEmpty()) {
                    val first = translations[0]
                    val translationId = first.translation.id
                    for (player in first.player) {
                        val epUrl = "$mainUrl/api/player/\( animeId/episodes?take=100&skip=-1&playerId= \){player.id}&translationId=$translationId"
                        val epJson = fetchJsonOrNull(epUrl)
                        if (epJson != null) {
                            val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                            if (!eps.isNullOrEmpty()) {
                                eps.forEach { ep ->
                                    episodes.add(newEpisode("$animeId, ${ep.episode}") {
                                        name = "Епізод ${ep.episode}"
                                        posterUrl = ep.poster
                                        episode = ep.episode
                                        data = "$animeId, ${ep.episode}"
                                    })
                                }
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                posterUrl = posterApi.format(animeJSON.image.preview)
                engName = animeJSON.titleEn
                tags = animeJSON.genres.map { it.nameUa }
                plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                duration = extractIntFromString(animeJSON.episodeTime)
                year = animeJSON.releaseDate.toIntOrNull()
                score = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
            }
        } else {
            val backgroundImage = animeJSON.backgroundImage.takeIf { it.isNotBlank() }
                ?: posterApi.format(animeJSON.image.preview)

            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, "$animeId") {
                posterUrl = posterApi.format(animeJSON.image.preview)
                tags = animeJSON.genres.map { it.nameUa }
                plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                duration = extractIntFromString(animeJSON.episodeTime)
                year = animeJSON.releaseDate.toIntOrNull()
                backgroundPosterUrl = backgroundImage
                score = Score.from10(animeJSON.rating)
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

        val translationsJson = fetchJsonOrNull("\( mainUrl/api/player/ \){dataList[0]}/translations") ?: return false
        val translations = try {
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
        } catch (e: Exception) { return false }

        translations.forEach { item ->
            val translationId = item.translation.id
            for (player in item.player) {
                val epUrl = "\( mainUrl/api/player/ \){dataList[0]}/episodes?take=100&skip=-1&playerId=${player.id}&translationId=$translationId"
                val epJson = fetchJsonOrNull(epUrl) ?: continue

                val episode = try {
                    Gson().fromJson(epJson, PlayerEpisodes::class.java)
                        .episodes?.firstOrNull { it.episode == dataList[1].toIntOrNull() }
                } catch (e: Exception) { null } ?: continue

                // Ashdi
                episode.fileUrl?.let { fileUrl ->
                    M3u8Helper.generateM3u8(
                        source = "\( {item.translation.name} ( \){player.name}) - Ashdi",
                        streamUrl = fileUrl,
                        referer = "https://ashdi.vip"
                    ).dropLast(1).forEach(callback)
                }

                // Moon
                episode.videoUrl?.let { videoUrl ->
                    if (videoUrl.contains("moonanime.art")) {
                        val m3u8 = getMoonM3U(videoUrl)
                        if (m3u8.isNotEmpty()) {
                            M3u8Helper.generateM3u8(
                                source = "\( {item.translation.name} ( \){player.name}) - Moon",
                                streamUrl = m3u8,
                                referer = "https://moonanime.art/"
                            ).dropLast(1).forEach(callback)
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun getMoonM3U(iframeUrl: String): String {
        return try {
            val response = app.get(iframeUrl, headers = mapOf(
                "Referer" to "https://animeon.club/",
                "Origin" to "https://animeon.club",
                "User-Agent" to userAgent
            ))

            val html = response.body.string()

            val regex = Regex("""https://s\.moonanime\.art/content/stream/anime/\d+/[a-zA-Z0-9]+/hls/[^"'\s<>]+?\.m3u8[^"'\s<>]*""")
            regex.find(html)?.value ?: run {
                val broadRegex = Regex("""https://s\.moonanime\.art[^\s"']+\.m3u8[^\s"']*""")
                broadRegex.find(html)?.value ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractIntFromString(string: String): Int? {
        return Regex("""\d+""").findAll(string).lastOrNull()?.value?.toIntOrNull()
    }
}
