package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private suspend fun fetchJson(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).text
            if (response.isBlank() || (!response.startsWith("{") && !response.startsWith("["))) null 
            else response
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val jsonText = fetchJson(url) ?: return newHomePageResponse(request.name, emptyList())

        return try {
            val animeList = if (request.data.contains("pageSize")) {
                Gson().fromJson(jsonText, NewAnimeModel::class.java).results
            } else {
                Gson().fromJson(jsonText, Array<Results>::class.java).toList()
            }

            newHomePageResponse(request.name, animeList.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJson("$apiUrl?search=$query") ?: return emptyList()
        return try {
            Gson().fromJson(jsonText, NewAnimeModel::class.java).results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: throw Exception("Invalid ID")
        val jsonText = fetchJson("$apiUrl/$animeId") ?: throw Exception("Data not found")
        val anime = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        // ВИПРАВЛЕНО: Явне вказання типу для уникнення конфлікту (Ambiguity)
        val episodesList = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJson("$mainUrl/api/player/$animeId/translations")
        
        if (translationsJson != null) {
            val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
            val seenEpisodes = mutableSetOf<Int>()
            
            translations.forEach { transItem ->
                transItem.player.forEach { player ->
                    val epJson = fetchJson("$mainUrl/api/player/$animeId/episodes?take=100&skip=0&playerId=${player.id}&translationId=${transItem.translation.id}")
                    if (epJson != null) {
                        // ВИПРАВЛЕНО: Додано .orEmpty() для безпечної роботи з nullable списком
                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes.orEmpty().forEach { ep ->
                            if (seenEpisodes.add(ep.episode)) {
                                episodesList.add(newEpisode("$animeId|${ep.episode}|${ep.id}") {
                                    this.name = "Епізод ${ep.episode}"
                                    this.episode = ep.episode
                                })
                            }
                        }
                    }
                }
            }
        }

        return newAnimeLoadResponse(anime.titleUa, url, TvType.Anime) {
            this.posterUrl = posterApi.format(anime.image.preview)
            this.plot = anime.description
            this.year = anime.releaseDate.split("-").firstOrNull()?.toIntOrNull()
            this.tags = anime.genres.map { it.nameUa }
            addEpisodes(DubStatus.Dubbed, episodesList.sortedBy { it.episode })
            addTrailer(anime.trailer)
            addMalId(anime.malId.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val splitData = data.split("|")
        if (splitData.size < 3) return false
        val epId = splitData[2]
        
        val epDetailJson = fetchJson("$mainUrl/api/player/$epId/episode") ?: return false
        val epData = Gson().fromJson(epDetailJson, FundubEpisode::class.java)

        if (!epData.fileUrl.isNullOrBlank()) {
            callback(
                ExtractorLink(
                    source = "AnimeON",
                    name = "AnimeON Direct",
                    url = epData.fileUrl!!,
                    referer = "https://ashdi.vip/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        if (!epData.videoUrl.isNullOrBlank() && epData.videoUrl!!.contains("ashdi")) {
            val ashdiUrl = if (epData.videoUrl!!.startsWith("http")) epData.videoUrl!! else "https:${epData.videoUrl}"
            val html = app.get(ashdiUrl, headers = mapOf("Referer" to "$mainUrl/")).text
            val m3u8 = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            
            if (m3u8 != null) {
                M3u8Helper.generateM3u8("Ashdi", m3u8, "https://ashdi.vip/").forEach(callback)
            }
        }

        return true
    }
}
