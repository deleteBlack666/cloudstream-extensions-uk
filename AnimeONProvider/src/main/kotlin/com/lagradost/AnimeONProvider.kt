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
    override var name = "AnimeON (заблоковано Cloudflare)"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$apiUrl/search?text="

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
        val json = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())
        val list = try {
            val model = Gson().fromJson(json, NewAnimeModel::class.java)
            model.results?.mapNotNull { it?.let { r ->
                newAnimeSearchResponse(r.titleUa, "anime/${r.id}", TvType.Anime) {
                    posterUrl = posterApi.format(r.image?.preview ?: "")
                }
            } } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val json = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        val model = try { Gson().fromJson(json, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        return model.result?.mapNotNull { it?.let { r ->
            newAnimeSearchResponse(r.titleUa, "anime/${r.id}", TvType.Anime) {
                posterUrl = posterApi.format(r.image?.preview ?: "")
                addDubStatus(isDub = true, r.episodes)
            }
        } } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiPath = url.replace("/anime/", "/api/anime/")
        val json = fetchJsonOrNull(apiPath) ?: throw Exception("API blocked by Cloudflare")
        val anime = try { Gson().fromJson(json, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("Parse error") }
        val id = anime.id ?: throw Exception("No ID")

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val transJson = fetchJsonOrNull("$mainUrl/api/player/translations/$id")
        if (transJson != null) {
            try {
                val trans = Gson().fromJson(transJson, TranslationResponse::class.java)
                val translation = trans.translations.firstOrNull { it.player.isNotEmpty() }
                if (translation != null) {
                    val player = translation.player.first()
                    val epJson = fetchJsonOrNull("$mainUrl/api/player/episodes?playerId=${player.id}&translationId=${translation.id}")
                    if (epJson != null) {
                        val playerJson = Gson().fromJson(epJson, PlayerJson::class.java)
                        playerJson.folder.forEach { season ->
                            season.folder.forEach { ep ->
                                episodes.add(newEpisode("$id|${player.id}|${translation.id}|${season.title}|${ep.title}") {
                                    name = "${season.title} - Серія ${ep.title}"
                                    posterUrl = ep.poster
                                    this.episode = ep.title.toIntOrNull() ?: 0
                                    data = ep.file
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        return newAnimeLoadResponse(anime.titleUa ?: "?", "$mainUrl/anime/$id", TvType.Anime) {
            posterUrl = posterApi.format(anime.image?.preview ?: "")
            engName = anime.titleEn
            tags = anime.genres?.mapNotNull { it?.nameUa } ?: emptyList()
            plot = anime.description
            addTrailer(anime.trailer)
            showStatus = if (anime.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(anime.malId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http")) {
            M3u8Helper.generateM3u8("AnimeON", data, mainUrl).dropLast(1).forEach(callback)
            return true
        }
        return false
    }
}
