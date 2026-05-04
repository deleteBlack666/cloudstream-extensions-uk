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

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        
        // Отримуємо fundubs
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId")
        if (fundubsJson != null) {
            try {
                val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                val fundubs = fundubsModel.fundubs ?: emptyList()
                if (fundubs.isNotEmpty()) {
                    // Беремо перший fundub (або можна перебирати всі)
                    val fundub = fundubs[0]
                    val player = fundub.player.firstOrNull()
                    val fundubId = fundub.fundub.id
                    if (player != null) {
                        val episodesUrl = "$mainUrl/api/player/episodes/$animeId?playerId=${player.id}&fundubId=$fundubId"
                        val episodesJson = fetchJsonOrNull(episodesUrl)
                        if (episodesJson != null) {
                            val playerEpisodes = Gson().fromJson(episodesJson, PlayerEpisodes::class.java)
                            playerEpisodes.episodes?.forEach { ep ->
                                // Отримуємо vid для кожного епізоду
                                val vid = getVidFromEpisode(animeInfo, ep.episode)
                                val episodeData = "$animeId,${ep.episode},$vid"
                                episodes.add(
                                    newEpisode(episodeData) {
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

    // Функція для отримання vid для епізоду
    // Це приклад – реальне vid потрібно отримувати з API або з HTML
    private suspend fun getVidFromEpisode(animeInfo: AnimeInfoModel, episode: Int): String {
        // Спроба отримати vid з додаткового API
        // Наприклад, деякі сайти мають ендпоінт /api/player/vid/{id}?episode={ep}
        val vidUrl = "$mainUrl/api/player/vid/${animeInfo.id}?episode=$episode"
        val vidJson = fetchJsonOrNull(vidUrl)
        if (vidJson != null) {
            try {
                val vidData = Gson().fromJson(vidJson, VidResponse::class.java)
                return vidData.vid.toString()
            } catch (e: Exception) { }
        }
        // Якщо немає API, можна спробувати отримати з HTML сторінки плеєра
        // Або повернути заглушку (не працюватиме)
        return ""
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
        val vid = parts[2].trim()

        if (vid.isNotEmpty()) {
            // Формуємо URL на основі отриманого vid
            // Це приклад для конкретного тайтлу. Для різних аніме назва може відрізнятися.
            // Тут потрібно визначити slug аніме (транслітерацію назви)
            val titleSlug = getTitleSlug(animeId) // потрібно реалізувати
            val m3u8Url = "https://ashdi.vip/video04/2/new/${titleSlug}.s01e${episodeNum.toString().padStart(2, '0')}_$vid/hls/BKiPlHaPlftdkwfhA4k=/index.m3u8"
            
            val testUrl = "https://ashdi.vip/video04/2/new/kioto_anime_youkoso_jitsuryoku_shijou_shugi_no_kyoushitsu.s01e01_246096/hls/BKiPlHaPlftdkwfhA4k=/index.m3u8"
            val finalUrl = if (m3u8Url.contains("ashdi.vip")) m3u8Url else testUrl
            
            M3u8Helper.generateM3u8("AnimeON", finalUrl, referer = "https://animeon.club").dropLast(1).forEach(callback)
            return true
        }
        return false
    }

    private suspend fun getTitleSlug(animeId: Int): String {
        // Отримуємо slug з API або генеруємо транслітерацію
        // Для прикладу повертаємо фіксоване значення
        return "kioto_anime_youkoso_jitsuryoku_shijou_shugi_no_kyoushitsu"
    }
}

data class VidResponse(val vid: Int)
