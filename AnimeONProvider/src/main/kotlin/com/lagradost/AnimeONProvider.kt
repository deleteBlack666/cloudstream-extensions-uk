package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*

// Тимчасові класи для головної сторінки (якщо не вистачає моделей)
data class TempAnimeItem(val id: Int, val titleUa: String, val image: TempImage?)
data class TempImage(val preview: String)

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
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті"
    )

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            if (response.contains("cloudflare", ignoreCase = true) ||
                response.contains("cf-browser-verification", ignoreCase = true) ||
                (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
            ) {
                log("Cloudflare blocked: $url")
                null
            } else response
        } catch (e: Exception) {
            log("Fetch error $url: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val url = request.data.format(page)
        val json = fetchJsonOrNull(url) ?: return newHomePageResponse(request.name, emptyList())
        val list = try {
            // Спроба як об'єкт NewAnimeModel
            val model = Gson().fromJson(json, NewAnimeModel::class.java)
            model.results?.mapNotNull { it?.let { r ->
                newAnimeSearchResponse(r.titleUa, "anime/${r.id}", TvType.Anime) {
                    posterUrl = posterApi.format(r.image?.preview ?: "")
                }
            } } ?: emptyList()
        } catch (e: Exception) {
            // Спроба як масив TempAnimeItem
            try {
                val type = object : TypeToken<Array<TempAnimeItem>>() {}.type
                Gson().fromJson<Array<TempAnimeItem>>(json, type).map { item ->
                    newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                        posterUrl = posterApi.format(item.image?.preview ?: "")
                    }
                }
            } catch (e2: Exception) {
                log("Main page parse error: ${e2.message}")
                emptyList()
            }
        }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val json = fetchJsonOrNull(searchApi + query) ?: return emptyList()
        val model = try {
            Gson().fromJson(json, SearchModel::class.java)
        } catch (e: Exception) {
            return emptyList()
        }
        return model.result?.mapNotNull { it?.let { r ->
            newAnimeSearchResponse(r.titleUa, "anime/${r.id}", TvType.Anime) {
                posterUrl = posterApi.format(r.image?.preview ?: "")
                addDubStatus(isDub = true, r.episodes)
            }
        } } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiPath = url.replace("/anime/", "/api/anime/")
        val json = fetchJsonOrNull(apiPath) ?: throw Exception("No data from $apiPath")
        val anime = try {
            Gson().fromJson(json, AnimeInfoModel::class.java)
        } catch (e: Exception) {
            throw Exception("JSON parse error: ${e.message}")
        }
        val id = anime.id ?: throw Exception("Anime ID missing")

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$id")
        log("Fundubs response for $id: ${fundubsJson?.take(300)}")
        
        if (fundubsJson != null) {
            try {
                val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                val fundubs = fundubsModel.fundubs ?: emptyList()
                log("Fundubs count: ${fundubs.size}")
                for (dub in fundubs) {
                    val players = dub.player ?: emptyList()
                    if (players.isNotEmpty()) {
                        val player = players.first()
                        val fundubId = dub.fundub?.id
                        if (fundubId != null) {
                            val epUrl = "$mainUrl/api/player/episodes/$id?playerId=${player.id}&fundubId=$fundubId"
                            val epJson = fetchJsonOrNull(epUrl)
                            if (epJson != null) {
                                val epData = Gson().fromJson(epJson, PlayerEpisodes::class.java)
                                val eps = epData.episodes ?: emptyList()
                                log("Episodes found: ${eps.size}")
                                eps.forEach { ep ->
                                    episodes.add(newEpisode("$id, ${ep.episode}") {
                                        name = "Епізод ${ep.episode}"
                                        posterUrl = ep.poster
                                        this.episode = ep.episode
                                        data = "$id, ${ep.episode}"
                                    })
                                }
                                if (episodes.isNotEmpty()) break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("Error loading episodes: ${e.message}")
                e.printStackTrace()
            }
        }
        log("Total episodes loaded: ${episodes.size}")

        val status = when {
            anime.status?.contains("ongoing") == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
        val tvType = when {
            anime.type?.contains("tv") == true -> TvType.Anime
            anime.type?.contains("OVA") == true -> TvType.OVA
            anime.type?.contains("ONA") == true -> TvType.OVA
            anime.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(anime.titleUa ?: "Без назви", "$mainUrl/anime/$id", tvType) {
            posterUrl = posterApi.format(anime.image?.preview ?: "")
            engName = anime.titleEn
            tags = anime.genres?.mapNotNull { it?.nameUa } ?: emptyList()
            plot = anime.description
            addTrailer(anime.trailer)
            this.showStatus = status
            duration = Regex("(\\d+)").find(anime.episodeTime ?: "")?.value?.toIntOrNull()
            year = anime.releaseDate?.toIntOrNull()
            score = anime.rating?.let { Score.from10(it) }
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
        val parts = data.split(", ")
        if (parts.size < 2) return false
        val id = parts[0]
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$id") ?: return false
        val fundubs = try {
            Gson().fromJson(fundubsJson, FundubsModel::class.java).fundubs ?: emptyList()
        } catch (e: Exception) {
            return false
        }
        var success = false
        for (dub in fundubs) {
            val player = dub.player?.firstOrNull()
            val fundub = dub.fundub
            if (player != null && fundub != null) {
                val videoUrl = app.get(
                    "$apiUrl/player/$id/${player.id}/${fundub.id}",
                    headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)
                ).parsedSafe<FundubVideoUrl>()?.videoUrl
                if (!videoUrl.isNullOrEmpty()) {
                    val m3u = getM3U(videoUrl)
                    if (m3u.isNotEmpty()) {
                        M3u8Helper.generateM3u8("${fundub.name} (${player.name})", m3u, mainUrl)
                            .dropLast(1).forEach(callback)
                        success = true
                    }
                }
            }
        }
        return success
    }

    private suspend fun getM3U(url: String): String {
        val html = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).document.select("script").html()
        return if (html.contains("cloudflare", ignoreCase = true)) "" else fileRegex.find(html)?.groupValues?.get(1) ?: ""
    }
}
