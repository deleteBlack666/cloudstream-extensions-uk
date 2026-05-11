package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private suspend fun getAshdiPoster(vodId: String): String? {
        return try {
            val html = app.get(
                "https://ashdi.vip/vod/$vodId?player=animeon.club",
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).text
            Regex("""poster:"(https?://[^"]+)"""").find(html)?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    // Збирає епізоди перебираючи translationId від 1 до maxId
    private suspend fun fetchEpisodesByBruteForce(
        animeId: Int,
        playerNames: List<String>
    ): List<FundubEpisode> {
        val result = mutableListOf<FundubEpisode>()
        val seen = mutableSetOf<Int>()

        // Перебираємо translationId від 1 до 2000
        for (translationId in 1..2000) {
            // Для кожного translationId перебираємо playerId від 1 до 10000
            // Але це занадто повільно — натомість використовуємо відомі назви плеєрів
            // і шукаємо через /episodes з skip=-1
            val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&translationId=$translationId"
            val epJson = fetchJsonOrNull(epUrl) ?: continue
            val eps = try {
                Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
            } catch (e: Exception) { null } ?: continue

            if (eps.isEmpty()) continue

            for (ep in eps) {
                if (seen.add(ep.episode)) {
                    result.add(ep)
                }
            }
            if (result.size >= 100) break
        }
        return result
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())

            val currentDate = java.text.SimpleDateFormat(
                "EEE MMM dd yyyy",
                java.util.Locale.ENGLISH
            ).format(java.util.Date())

            val jsonText = fetchJsonOrNull(
                "${request.data}$currentDate?withView=false"
            ) ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)

            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }

        if (request.data.contains("seasons") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page)
            else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

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

    private suspend fun collectEpisodes(
        animeId: Int,
        translations: List<TranslationItem>
    ): List<com.lagradost.cloudstream3.Episode> {
        val episodePosterMap = mutableMapOf<Int, String>()
        val episodeIdMap = mutableMapOf<Int, Int>()
        val episodeAshdiVodMap = mutableMapOf<Int, String>()

        for (translation in translations) {
            val translationId = translation.translation.id
            val sortedPlayers = translation.player.sortedBy {
                if (it.name.contains("Moon", ignoreCase = true)) 0 else 1
            }

            for (player in sortedPlayers) {
                val collected = mutableListOf<FundubEpisode>()

                for (offset in 0..5000 step 100) {
                    val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=$translationId"
                    val epJson = fetchJsonOrNull(epUrl) ?: break
                    val eps = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                    } catch (e: Exception) { null }

                    if (eps.isNullOrEmpty()) break
                    collected.addAll(eps)
                    if (eps.size < 100) break
                }

                for (ep in collected) {
                    if (!episodeIdMap.containsKey(ep.episode)) {
                        episodeIdMap[ep.episode] = ep.id
                    }
                    if (ep.poster.isNotEmpty() && !episodePosterMap.containsKey(ep.episode)) {
                        episodePosterMap[ep.episode] = ep.poster
                    }
                    if (!episodeAshdiVodMap.containsKey(ep.episode) &&
                        !ep.videoUrl.isNullOrEmpty() &&
                        ep.videoUrl!!.contains("ashdi.vip/vod/")) {
                        val vodId = ep.videoUrl!!.substringAfterLast("/")
                        if (vodId.isNotEmpty()) episodeAshdiVodMap[ep.episode] = vodId
                    }
                }
            }
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (epNum in episodeIdMap.keys.sorted()) {
            val epId = episodeIdMap[epNum] ?: continue
            var poster: String? = episodePosterMap[epNum]
            if (poster.isNullOrEmpty()) {
                val vodId = episodeAshdiVodMap[epNum]
                if (!vodId.isNullOrEmpty()) poster = getAshdiPoster(vodId)
            }
            episodes.add(newEpisode("$animeId, $epNum, $epId") {
                this.name = "Епізод $epNum"
                this.posterUrl = poster
                this.episode = epNum
                this.data = "$animeId, $epNum, $epId"
            })
        }
        return episodes
    }

    // Fallback: шукає епізоди коли /translations дає 404
    // Використовує назви плеєрів з animeJSON.players
    private suspend fun collectEpisodesFallback(
        animeId: Int,
        playerNames: List<String>
    ): List<com.lagradost.cloudstream3.Episode> {
        val episodePosterMap = mutableMapOf<Int, String>()
        val episodeIdMap = mutableMapOf<Int, Int>()
        val episodeAshdiVodMap = mutableMapOf<Int, String>()

        // Сортуємо — Moon першим
        val sortedNames = playerNames.sortedBy {
            if (it.contains("Moon", ignoreCase = true)) 0 else 1
        }

        for (playerName in sortedNames) {
            // Шукаємо епізоди перебираючи translationId
            for (translationId in 1000..2000) {
                // Спробуємо знайти playerId через відомі діапазони
                for (playerId in 1..20000 step 500) {
                    val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=$playerId&translationId=$translationId"
                    val epJson = fetchJsonOrNull(epUrl) ?: continue
                    val eps = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                    } catch (e: Exception) { null }

                    if (!eps.isNullOrEmpty()) {
                        for (ep in eps) {
                            if (!episodeIdMap.containsKey(ep.episode)) {
                                episodeIdMap[ep.episode] = ep.id
                            }
                            if (ep.poster.isNotEmpty() && !episodePosterMap.containsKey(ep.episode)) {
                                episodePosterMap[ep.episode] = ep.poster
                            }
                            if (!episodeAshdiVodMap.containsKey(ep.episode) &&
                                !ep.videoUrl.isNullOrEmpty() &&
                                ep.videoUrl!!.contains("ashdi.vip/vod/")) {
                                episodeAshdiVodMap[ep.episode] = ep.videoUrl!!.substringAfterLast("/")
                            }
                        }
                        break
                    }
                }
                if (episodeIdMap.isNotEmpty()) break
            }
            if (episodeIdMap.isNotEmpty()) break
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (epNum in episodeIdMap.keys.sorted()) {
            val epId = episodeIdMap[epNum] ?: continue
            var poster: String? = episodePosterMap[epNum]
            if (poster.isNullOrEmpty()) {
                val vodId = episodeAshdiVodMap[epNum]
                if (!vodId.isNullOrEmpty()) poster = getAshdiPoster(vodId)
            }
            episodes.add(newEpisode("$animeId, $epNum, $epId") {
                this.name = "Епізод $epNum"
                this.posterUrl = poster
                this.episode = epNum
                this.data = "$animeId, $epNum, $epId"
            })
        }
        return episodes
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val jsonText = fetchJsonOrNull("$apiUrl/$animeId")
            ?: throw Exception("Failed to load")
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

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                episodes.addAll(collectEpisodes(animeId, translations))
            } catch (e: Exception) { }
        } else {
            // Fallback: /translations дав 404, використовуємо players з animeJSON
            val playerNames = animeJSON.players ?: emptyList()
            if (playerNames.isNotEmpty()) {
                // Шукаємо через відомий endpoint з hikka/animeon tracking
                val trackingJson = fetchJsonOrNull("$mainUrl/api/user/anime-episode-tracking/$animeId/stats")
                if (trackingJson != null) {
                    try {
                        // Отримуємо translationName зі stats і шукаємо відповідні епізоди
                        val stats = Gson().fromJson(trackingJson, TrackingStats::class.java)
                        // Для кожного translationName шукаємо playerId через перебір невеликого діапазону
                        for (translationStat in stats.translations ?: emptyList()) {
                            // Шукаємо епізоди через /episodes з різними translationId
                            var found = false
                            for (translationId in 1000..2000) {
                                if (found) break
                                for (playerId in listOf(
                                    3466, 3888, 3774, 3792, 7745, 7927,
                                    308, 266, 5538, 5539, 5540
                                )) {
                                    val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=$playerId&translationId=$translationId"
                                    val epJson = fetchJsonOrNull(epUrl) ?: continue
                                    val eps = try {
                                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                                    } catch (e: Exception) { null }
                                    if (!eps.isNullOrEmpty()) {
                                        found = true
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { }
                }

                // Прямий пошук через відомі playerId
                val knownPlayerIds = listOf(
                    3466, 3888, 3774, 3792, 7745, 7927,
                    308, 266, 5538, 5539, 5540, 1, 2, 3, 4, 5
                )
                val knownTranslationIds = listOf(
                    1093, 1098, 1105, 1110, 1167, 1179, 1400
                )

                val episodePosterMap = mutableMapOf<Int, String>()
                val episodeIdMap = mutableMapOf<Int, Int>()
                val episodeAshdiVodMap = mutableMapOf<Int, String>()

                // Moon першим
                val sortedPlayerIds = knownPlayerIds.sortedBy { id ->
                    if (playerNames.any { it.contains("Moon", ignoreCase = true) } &&
                        id in listOf(3888, 7927)) 0 else 1
                }

                for (translationId in knownTranslationIds) {
                    for (playerId in sortedPlayerIds) {
                        val epUrl = "$mainUrl/api/player/$animeId/episodes?take=100&skip=-1&playerId=$playerId&translationId=$translationId"
                        val epJson = fetchJsonOrNull(epUrl) ?: continue
                        val eps = try {
                            Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                        } catch (e: Exception) { null }

                        if (eps.isNullOrEmpty()) continue

                        for (ep in eps) {
                            if (!episodeIdMap.containsKey(ep.episode)) {
                                episodeIdMap[ep.episode] = ep.id
                            }
                            if (ep.poster.isNotEmpty() && !episodePosterMap.containsKey(ep.episode)) {
                                episodePosterMap[ep.episode] = ep.poster
                            }
                            if (!episodeAshdiVodMap.containsKey(ep.episode) &&
                                !ep.videoUrl.isNullOrEmpty() &&
                                ep.videoUrl!!.contains("ashdi.vip/vod/")) {
                                episodeAshdiVodMap[ep.episode] = ep.videoUrl!!.substringAfterLast("/")
                            }
                        }
                    }
                }

                for (epNum in episodeIdMap.keys.sorted()) {
                    val epId = episodeIdMap[epNum] ?: continue
                    var poster: String? = episodePosterMap[epNum]
                    if (poster.isNullOrEmpty()) {
                        val vodId = episodeAshdiVodMap[epNum]
                        if (!vodId.isNullOrEmpty()) poster = getAshdiPoster(vodId)
                    }
                    episodes.add(newEpisode("$animeId, $epNum, $epId") {
                        this.name = "Епізод $epNum"
                        this.posterUrl = poster
                        this.episode = epNum
                        this.data = "$animeId, $epNum, $epId"
                    })
                }
            }
        }

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.engName = animeJSON.titleEn
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank())
                posterApi.format(animeJSON.image.preview)
            else
                animeJSON.backgroundImage

            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, "$animeId") {
                this.posterUrl = posterApi.format(animeJSON.image.preview)
                this.tags = animeJSON.genres.map { it.nameUa }
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)
                addMalId(animeJSON
