package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*
import java.net.URLEncoder

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
            ) null else response
        } catch (e: Exception) { null }
    }

    // Отримуємо slug аніме (унікальний ідентифікатор у URL)
    private suspend fun getSlug(animeId: Int): String? {
        val json = fetchJsonOrNull("$mainUrl/api/anime/$animeId") ?: return null
        val info = try { Gson().fromJson(json, AnimeInfoModel::class.java) } catch (e: Exception) { return null }
        return info.slug?.takeIf { it.isNotEmpty() }
    }

    // Отримуємо vid для конкретного епізоду (з API або HTML)
    private suspend fun getVid(animeId: Int, episode: Int): String? {
        // Спроба отримати з API (якщо є такий ендпоінт)
        val vidUrl = "$mainUrl/api/player/vid/$animeId?episode=$episode"
        val vidJson = fetchJsonOrNull(vidUrl)
        if (vidJson != null) {
            try {
                val data = Gson().fromJson(vidJson, VidResponse::class.java)
                if (data.vid > 0) return data.vid.toString()
            } catch (e: Exception) { }
        }
        // Резерв: парсимо HTML сторінки плеєра
        val watchUrl = "$mainUrl/anime/$animeId/watch?ep=$episode"
        val doc = app.get(watchUrl).document
        val scripts = doc.select("script").eachText().joinToString("\n")
        val pattern = Regex("""vid[^0-9]*[:=]\s*["']?(\d+)["']?""")
        val match = pattern.find(scripts)
        return match?.groupValues?.get(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())
        val homeList = try {
            val model = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            model.results?.mapNotNull { result ->
                result?.let {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        posterUrl = posterApi.format(it.image.preview)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            try {
                val arrayType = object : TypeToken<Array<AnimeModel>>() {}.type
                val items: Array<AnimeModel> = Gson().fromJson(jsonText, arrayType)
                items.map { item ->
                    newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                        posterUrl = posterApi.format(item.image.preview)
                    }
                }
            } catch (e2: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        val searchModel = try { Gson().fromJson(jsonText, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        return searchModel.result?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath) ?: throw Exception("Не вдалося завантажити інформацію про аніме")
        val animeInfo = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("Помилка парсингу JSON") }
        val animeId = animeInfo.id
        val slug = animeInfo.slug ?: getSlug(animeId) ?: "unknown"

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId")
        if (fundubsJson != null) {
            try {
                val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                val fundubs = fundubsModel.fundubs ?: emptyList()
                if (fundubs.isNotEmpty()) {
                    val fundub = fundubs[0]
                    val player = fundub.player.firstOrNull()
                    val fundubId = fundub.fundub.id
                    if (player != null) {
                        val episodesUrl = "$mainUrl/api/player/episodes/$animeId?playerId=${player.id}&fundubId=$fundubId"
                        val episodesJson = fetchJsonOrNull(episodesUrl)
                        if (episodesJson != null) {
                            val playerEpisodes = Gson().fromJson(episodesJson, PlayerEpisodes::class.java)
                            playerEpisodes.episodes?.forEach { ep ->
                                episodes.add(
                                    newEpisode("$animeId,${ep.episode},$slug") {
                                        name = "Епізод ${ep.episode}"
                                        posterUrl = ep.poster
                                        this.episode = ep.episode
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        val showStatus = if (animeInfo.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = when {
            animeInfo.type?.contains("tv") == true -> TvType.Anime
            animeInfo.type?.contains("OVA") == true -> TvType.OVA
            animeInfo.type?.contains("ONA") == true -> TvType.OVA
            animeInfo.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(animeInfo.titleUa, "$mainUrl/anime/$animeId", tvType) {
            posterUrl = posterApi.format(animeInfo.image.preview)
            engName = animeInfo.titleEn
            tags = animeInfo.genres?.map { it.nameUa } ?: emptyList()
            plot = animeInfo.description
            addTrailer(animeInfo.trailer)
            this.showStatus = showStatus
            year = animeInfo.releaseDate.toIntOrNull()
            score = animeInfo.rating?.toDoubleOrNull()?.let { Score.from10(it) }
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeInfo.malId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        if (parts.size < 3) return false
        val animeId = parts[0].trim().toIntOrNull() ?: return false
        val episodeNum = parts[1].trim().toIntOrNull() ?: return false
        val slug = parts[2].trim()

        val vid = getVid(animeId, episodeNum)
        if (vid != null && slug.isNotEmpty()) {
            // Формуємо сезон (за замовчуванням 1, можна отримувати з іншого API)
            val season = 1
            val seasonStr = season.toString().padStart(2, '0')
            val episodeStr = episodeNum.toString().padStart(2, '0')
            val m3u8Url = "https://ashdi.vip/video04/2/new/${slug}_s${seasonStr}e${episodeStr}_$vid/hls/BKiPlHaPlftdkwfhA4k=/index.m3u8"
            M3u8Helper.generateM3u8("AnimeON", m3u8Url, referer = "https://animeon.club").dropLast(1).forEach(callback)
            return true
        }
        return false
    }
}

data class VidResponse(val vid: Int)
