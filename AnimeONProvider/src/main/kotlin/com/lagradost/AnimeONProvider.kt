package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
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
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonText = if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            fetchJsonOrNull("${request.data}$currentDate?withView=false")
        } else if (request.data.contains("seasons")) {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            fetchJsonOrNull(request.data)
        } else {
            fetchJsonOrNull(request.data.format(page))
        } ?: return newHomePageResponse(request.name, emptyList())

        val results = if (request.name == "Нове аніме на сайті") {
            Gson().fromJson(jsonText, NewAnimeModel::class.java).results
        } else {
            Gson().fromJson<List<Results>>(jsonText, object : TypeToken<List<Results>>() {}.type)
        }

        return newHomePageResponse(request.name, results.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image?.preview)
            }
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        return try {
            Gson().fromJson(jsonText, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image?.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val episodes = mutableListOf<Episode>()
        val animePoster = posterApi.format(animeJSON.image?.preview)

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
            val seenEpisodes = mutableSetOf<Int>()

            for (translation in translations) {
                for (player in translation.player) {
                    for (offset in 0..1000 step 100) {
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=${translation.translation.id}"
                        val epJson = fetchJsonOrNull(epUrl) ?: break
                        val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes ?: break
                        if (eps.isEmpty()) break

                        eps.forEach { ep ->
                            if (seenEpisodes.add(ep.episode)) {
                                episodes.add(newEpisode("$animeId,${ep.episode},${ep.id}") {
                                    this.name = "Епізод ${ep.episode}"
                                    this.episode = ep.episode
                                    this.posterUrl = ep.poster?.takeIf { it.isNotBlank() } ?: animePoster
                                })
                            }
                        }
                        if (eps.size < 100) break
                    }
                }
            }
        }
        episodes.sortBy { it.episode }

        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", TvType.Anime) {
            this.posterUrl = animePoster
            this.plot = animeJSON.description
            this.tags = animeJSON.genres?.map { it.nameUa }
            this.year = animeJSON.releaseDate?.toIntOrNull()
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataList = data.split(",")
        val animeId = dataList[0]
        val targetEpisode = dataList[1].toInt()
        val episodeId = dataList.getOrNull(2)

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

        translations.forEach { item ->
            for (player in item.player) {
                val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=0&playerId=${player.id}&translationId=${item.translation.id}"
                val epJson = fetchJsonOrNull(epUrl) ?: continue
                val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes ?: continue
                val episode = eps.firstOrNull { it.episode == targetEpisode } ?: continue

                if (!episode.fileUrl.isNullOrEmpty()) {
                    M3u8Helper.generateM3u8(
                        source = "${item.translation.name} (${player.name})",
                        streamUrl = episode.fileUrl,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)
                }
            }
        }
        return true
    }
}
