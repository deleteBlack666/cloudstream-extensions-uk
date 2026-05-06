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
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$apiUrl?pageSize=24&pageIndex=%d&sort=rating" to "Популярне",
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
            if (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("[")) null
            else response
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())

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
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId")
            ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val showStatus = if (animeJSON.status.contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = when {
            animeJSON.type.contains("tv") -> TvType.Anime
            animeJSON.type.contains("movie") -> TvType.AnimeMovie
            else -> TvType.OVA
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                if (translations.isNotEmpty()) {
                    val first = translations[0]
                    val translationId = first.translation.id
                    for (player in first.player) {
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=${player.id}&translationId=$translationId"
                        val epJson = fetchJsonOrNull(epUrl)
                        if (epJson != null) {
                            val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                            eps?.forEach { ep ->
                                episodes.add(newEpisode("$animeId, ${ep.episode}") {
                                    this.name = "Епізод ${ep.episode}"
                                    this.posterUrl = ep.poster
                                    this.episode = ep.episode
                                    this.data = "$animeId, ${ep.episode}"
                                })
                            }
                            if (!eps.isNullOrEmpty()) break
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
            this.posterUrl = posterApi.format(animeJSON.image.preview)
            this.engName = animeJSON.titleEn
            this.plot = animeJSON.description
            this.tags = animeJSON.genres.map { it.nameUa }
            this.showStatus = showStatus
            this.year = animeJSON.releaseDate.toIntOrNull()
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
        if (dataList.size < 2) return false
        val animeId = dataList[0]
        val targetEp = dataList[1].toIntOrNull() ?: return false

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        val translations = try {
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
        } catch (e: Exception) { return false }

        translations.forEach { item ->
            val translationId = item.translation.id
            for (player in item.player) {
                val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=${player.id}&translationId=$translationId"
                val epJson = fetchJsonOrNull(epUrl) ?: continue

                val episode = try {
                    Gson().fromJson(epJson, PlayerEpisodes::class.java)
                        .episodes?.firstOrNull { it.episode == targetEp }
                } catch (e: Exception) { null } ?: continue

                // 1. Ashdi
                if (!episode.fileUrl.isNullOrEmpty()) {
                    M3u8Helper.generateM3u8(
                        source = "Ashdi: ${item.translation.name}",
                        streamUrl = episode.fileUrl!!,
                        referer = "https://ashdi.vip/"
                    ).forEach(callback)
                }

                // 2. Moon
                if (!episode.videoUrl.isNullOrEmpty() && episode.videoUrl!!.contains("moonanime")) {
                    val m3u8Url = getMoonM3U(episode.videoUrl!!)
                    if (m3u8Url.isNotEmpty()) {
                        M3u8Helper.generateM3u8(
                            source = "Moon: ${item.translation.name}",
                            streamUrl = m3u8Url,
                            referer = "https://moonanime.art/",
                            headers = mapOf(
                                "Origin" to "https://moonanime.art",
                                "Referer" to "https://moonanime.art/"
                            )
                        ).forEach(callback)
                    }
                }
            }
        }
        return true
    }

    private suspend fun getMoonM3U(iframeUrl: String): String {
        return try {
            val response = app.get(iframeUrl, headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent
            ))
            val html = response.text
            
            // Шукаємо m3u8 посилання
            val regex = """https?://s\.moonanime\.art/content/stream/[^"']+\.m3u8[^"']*""".toRegex()
            var link = regex.find(html)?.value ?: ""
            
            if (link.isEmpty()) {
                val altRegex = """//s\.moonanime\.art/content/stream/[^"']+\.m3u8[^"']*""".toRegex()
                link = altRegex.find(html)?.value?.let { "https:$it" } ?: ""
            }
            
            link
        } catch (e: Exception) { "" }
    }

    private fun extractIntFromString(string: String): Int? {
        return Regex("(\\d+)").find(string)?.value?.toIntOrNull()
    }
}
