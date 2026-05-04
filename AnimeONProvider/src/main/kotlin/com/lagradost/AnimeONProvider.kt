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
    override var name = "AnimeON (з логами)"
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
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове"
    )

    private suspend fun fetchJsonOrNull(url: String, tag: String = ""): String? {
        println("[$tag] Запит URL: $url")
        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            println("[$tag] Отримано відповідь, довжина: ${response.length}")
            if (response.contains("cloudflare", ignoreCase = true) ||
                response.contains("cf-browser-verification", ignoreCase = true) ||
                (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
            ) {
                println("[$tag] ПОМИЛКА: Схоже на Cloudflare або не JSON. Перші 200 символів: ${response.take(200)}")
                null
            } else {
                println("[$tag] JSON успішно отримано")
                response
            }
        } catch (e: Exception) {
            println("[$tag] Виняток: ${e.message}")
            null
        }
    }

    private fun anyToInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Double -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("getMainPage: page=$page, request=${request.name}")
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page), "MAIN") ?: return newHomePageResponse(request.name, emptyList())
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
            } catch (e2: Exception) {
                println("Помилка парсингу головної: ${e2.message}")
                emptyList()
            }
        }
        println("Отримано ${homeList.size} елементів для категорії ${request.name}")
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        println("Пошук запиту: $query -> $encoded")
        val jsonText = fetchJsonOrNull(searchApi + encoded, "SEARCH") ?: return emptyList()
        val searchModel = try { Gson().fromJson(jsonText, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        val results = searchModel.result?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } ?: emptyList()
        println("Знайдено ${results.size} результатів")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        println("load: $url")
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath, "LOAD_INFO") ?: throw Exception("Не вдалося завантажити інформацію про аніме")
        val animeInfo = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("Помилка парсингу JSON") }
        val animeId = animeInfo.id
        println("Отримано аніме: id=$animeId, назва=${animeInfo.titleUa}")

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        // Запит fundubs
        val fundubsUrl = "$mainUrl/api/player/fundubs/$animeId"
        val fundubsJson = fetchJsonOrNull(fundubsUrl, "FUNDUBS")
        println("fundubsJson: ${fundubsJson?.take(300)}")
        if (fundubsJson != null) {
            try {
                val fundubsModel = Gson().fromJson(fundubsJson, FundubsModel::class.java)
                val fundubs = fundubsModel.fundubs ?: emptyList()
                println("Кількість funDubs: ${fundubs.size}")
                if (fundubs.isNotEmpty()) {
                    val fundub = fundubs[0]
                    val player = fundub.player.firstOrNull()
                    val fundubId = fundub.fundub.id
                    println("Вибрано fundub: id=$fundubId, player=${player?.id}")
                    if (player != null) {
                        val episodesUrl = "$mainUrl/api/player/episodes/$animeId?playerId=${player.id}&fundubId=$fundubId"
                        val episodesJson = fetchJsonOrNull(episodesUrl, "EPISODES")
                        if (episodesJson != null) {
                            val playerEpisodes = Gson().fromJson(episodesJson, PlayerEpisodes::class.java)
                            val epsList = playerEpisodes.episodes ?: emptyList()
                            println("Отримано ${epsList.size} епізодів")
                            epsList.forEach { ep ->
                                episodes.add(
                                    newEpisode("$animeId,${ep.episode}") {
                                        name = "Епізод ${ep.episode}"
                                        posterUrl = ep.poster
                                        this.episode = ep.episode
                                    }
                                )
                            }
                        } else {
                            println("Не вдалося отримати episodesJson")
                        }
                    } else {
                        println("player == null")
                    }
                } else {
                    println("fundubs порожній")
                }
            } catch (e: Exception) {
                println("Помилка при обробці fundubs: ${e.message}")
            }
        } else {
            println("fundubsJson == null (ймовірно Cloudflare або помилка)")
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
            addMalId(anyToInt(animeInfo.malId))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("loadLinks отримано data: $data")
        val dataList = data.split(",")
        if (dataList.size < 2) {
            println("Неправильний формат data (менше 2 частин)")
            return false
        }
        val animeId = dataList[0].trim().toIntOrNull()
        val episodeNum = dataList[1].trim().toIntOrNull()
        if (animeId == null || episodeNum == null) {
            println("Не вдалося розібрати animeId або episodeNum: ${dataList[0]}, ${dataList[1]}")
            return false
        }
        println("Шукаємо посилання для animeId=$animeId, episode=$episodeNum")

        // Отримуємо fundubs
        val fundubsJson = fetchJsonOrNull("$mainUrl/api/player/fundubs/$animeId", "LINKS_FUNDUBS") ?: return false
        val fundubsModel = try { Gson().fromJson(fundubsJson, FundubsModel::class.java) } catch (e: Exception) { return false }
        val fundub = fundubsModel.fundubs?.firstOrNull() ?: return false
        val player = fundub.player.firstOrNull() ?: return false
        val fundubId = fundub.fundub.id
        println("Отримано fundubId=$fundubId, playerId=${player.id}")

        // Запит на отримання URL відео
        val videoInfoUrl = "$apiUrl/player/$animeId/${player.id}/$fundubId?episode=$episodeNum"
        val videoInfoJson = fetchJsonOrNull(videoInfoUrl, "VIDEO_URL") ?: return false
        val videoUrlData = try { Gson().fromJson(videoInfoJson, FundubVideoUrl::class.java) } catch (e: Exception) { return false }
        val videoUrl = videoUrlData.videoUrl
        println("Отримано videoUrl: $videoUrl")

        if (videoUrl.isNotEmpty()) {
            val m3u8Url = if (videoUrl.contains(".m3u8")) videoUrl else getM3U8FromPage(videoUrl)
            println("Фінальний m3u8 URL: $m3u8Url")
            if (m3u8Url.isNotEmpty()) {
                M3u8Helper.generateM3u8("AnimeON", m3u8Url, referer = mainUrl).dropLast(1).forEach(callback)
                return true
            } else {
                println("m3u8Url порожній")
            }
        } else {
            println("videoUrl порожній")
        }
        return false
    }

    private suspend fun getM3U8FromPage(url: String): String {
        println("getM3U8FromPage: $url")
        val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
        val html = response.document.select("script").html()
        if (html.contains("cloudflare", ignoreCase = true)) {
            println("Знайдено cloudflare в html")
            return ""
        }
        val match = fileRegex.find(html)?.groupValues?.get(1)
        println("Знайдено file: $match")
        return match ?: ""
    }
}
