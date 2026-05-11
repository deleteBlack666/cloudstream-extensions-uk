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

    // Допоміжна функція для виправлення посилань на зображення
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        val cleanUrl = if (url.startsWith("/")) url else "/$url"
        return "$mainUrl$cleanUrl"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
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
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())

            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false")
                ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = fixUrl(posterApi.format(it.image.preview))
                }
            })
        }

        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(request.name, emptyList())

        val jsonText = fetchJsonOrNull(if (request.data.contains("%d")) request.data.format(page) else request.data)
            ?: return newHomePageResponse(request.name, emptyList())

        return if (!request.data.contains("seasons")) {
            val parsedJSON = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            newHomePageResponse(request.name, parsedJSON.results.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = fixUrl(posterApi.format(it.image.preview))
                }
            })
        } else {
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = fixUrl(posterApi.format(it.image.preview))
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
                    this.posterUrl = fixUrl(posterApi.format(it.image.preview))
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId") ?: throw Exception("Failed to load")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val tvType = with(animeJSON.type) {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val animePoster = fixUrl(posterApi.format(animeJSON.image.preview))

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                val seenEpisodes = mutableSetOf<Int>()

                for (translation in translations) {
                    for (player in translation.player) {
                        // Збільшено поріг до 1000 для великих тайтлів як Наруто
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=1000&skip=0&playerId=${player.id}&translationId=${translation.translation.id}"
                        val epJson = fetchJsonOrNull(epUrl) ?: continue
                        val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes ?: continue

                        for (ep in eps) {
                            if (!seenEpisodes.add(ep.episode)) continue

                            // Виправлення прев'ю для епізодів
                            val episodePoster = when {
                                !ep.poster.isNullOrBlank() -> fixUrl(ep.poster)
                                !ep.fileUrl.isNullOrBlank() && ep.fileUrl.contains("ashdi.vip") -> {
                                    ep.fileUrl.substringBeforeLast("/") + "/screen.jpg"
                                }
                                else -> fixUrl(animeJSON.backgroundImage) ?: animePoster
                            }

                            episodes.add(newEpisode("$animeId,${ep.episode},${ep.id}") {
                                this.name = "Епізод ${ep.episode}"
                                this.posterUrl = episodePoster
                                this.episode = ep.episode
                            })
                        }
                    }
                }
                episodes.sortBy { it.episode }
            } catch (e: Exception) { }
        }

        return newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
            this.posterUrl = animePoster
            this.backgroundPosterUrl = fixUrl(animeJSON.backgroundImage) ?: animePoster
            this.engName = animeJSON.titleEn
            this.tags = animeJSON.genres.map { it.nameUa }
            this.plot = animeJSON.description
            addTrailer(animeJSON.trailer)
            this.showStatus = if (animeJSON.status.contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
            this.year = animeJSON.releaseDate.toIntOrNull()
            this.score = Score.from10(animeJSON.rating)
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
        val dataList = data.split(",")
        val animeId = dataList[0]
        val targetEpisode = dataList[1].toInt()

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

        translations.forEach { item ->
            for (player in item.player) {
                val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=0&playerId=${player.id}&translationId=${item.translation.id}"
                val epJson = fetchJsonOrNull(epUrl) ?: continue
                val eps = Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes ?: emptyList()
                val episode = eps.firstOrNull { it.episode == targetEpisode }

                // Твоя оригінальна логіка Ashdi
                episode?.fileUrl?.let { link ->
                    M3u8Helper.generateM3u8("${item.translation.name} (${player.name})", link, "https://ashdi.vip").forEach(callback)
                }
                
                // Твоя оригінальна логіка Moon
                episode?.videoUrl?.let { videoUrl ->
                    if (videoUrl.contains("moonanime.art")) {
                        val rawFile = getMoonFile(videoUrl)
                        if (rawFile.isNotEmpty()) {
                            M3u8Helper.generateM3u8("${item.translation.name} (${player.name}) Moon", rawFile, "https://moonanime.art/").forEach(callback)
                        }
                    }
                }
            }
        }
        return true
    }

    private suspend fun getMoonFile(iframeUrl: String): String {
        return try {
            val html = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl)).text
            val fileRegex = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
            fileRegex.find(html)?.groupValues?.get(1)?.let { moonDecrypt(it) } ?: ""
        } catch (e: Exception) { "" }
    }

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            val result = StringBuilder()
            for (i in decoded.indices) {
                result.append((decoded[i].toInt() and 0xFF xor key[i % key.length].code).toChar())
            }
            result.toString()
        } catch (e: Exception) { "" }
    }
}
