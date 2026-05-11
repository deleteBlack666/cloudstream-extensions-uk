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
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

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
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())

            val currentDate = java.text.SimpleDateFormat(
                "EEE MMM dd yyyy",
                java.util.Locale.ENGLISH
            ).format(java.util.Date())

            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false")
                ?: return newHomePageResponse(request.name, emptyList())

            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)

            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }

        if (request.data.contains("seasons") && page != 1)
            return newHomePageResponse(emptyList())

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

    override suspend fun quickSearch(query: String) = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()

        return try {
            Gson().fromJson(jsonText, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()

        val jsonText = fetchJsonOrNull("$apiUrl/$animeId")
            ?: throw Exception("Failed to load")

        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val showStatus =
            if (animeJSON.status.contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed

        val tvType = with(animeJSON.type) {
            when {
                contains("tv") -> TvType.Anime
                contains("OVA") || contains("ONA") || contains("Спеціальний випуск") -> TvType.OVA
                contains("movie") -> TvType.AnimeMovie
                else -> TvType.Anime
            }
        }

        // ===== SELECT BEST PLAYER + PREVIEW FIX =====

val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

val translationsJson =
    fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

if (translationsJson != null) {
    try {
        val translations =
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

        val seen = mutableSetOf<Int>()

        translations.forEach { translation ->

            // PRIORITY:
            // 1. Moon with poster
            // 2. Ashdi with fileUrl
            val sortedPlayers = translation.player.sortedByDescending { player ->
                when {
                    player.name.equals("Moon", true) -> 2
                    player.name.equals("Ashdi", true) -> 1
                    else -> 0
                }
            }

            sortedPlayers.forEach { player ->

                val collected = mutableListOf<FundubEpisode>()

                for (offset in 0..2000 step 100) {

                    val epUrl =
                        "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=${translation.translation.id}"

                    val epJson = fetchJsonOrNull(epUrl) ?: break

                    val eps = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                    } catch (e: Exception) {
                        null
                    }

                    if (eps.isNullOrEmpty()) break

                    collected.addAll(eps)

                    if (eps.size < 100) break
                }

                collected.forEach { ep ->

                    // якщо епізод вже є — але новий має poster, оновлюємо
                    val existing =
                        episodes.find { it.episode == ep.episode }

                    val betterPoster =
                        !ep.poster.isNullOrEmpty()

                    if (existing != null) {

                        // FIX чорних прев'ю:
                        // Moon poster > empty Ashdi poster
                        if (betterPoster) {
                            existing.posterUrl = ep.poster
                        }

                    } else if (seen.add(ep.episode)) {

                        episodes.add(
                            newEpisode("$animeId, ${ep.episode}, ${ep.id}") {

                                this.name = "Епізод ${ep.episode}"

                                // ГОЛОВНИЙ ФІКС
                                this.posterUrl =
                                    ep.poster.takeIf { !it.isNullOrEmpty() }

                                this.episode = ep.episode

                                this.data =
                                    "$animeId, ${ep.episode}, ${ep.id}"
                            }
                        )
                    }
                }
            }
        }

        episodes.sortBy { it.episode }

    } catch (_: Exception) {
    }
}

                episodes.sortBy { it.episode }

            } catch (_: Exception) {}
        }

        return newAnimeLoadResponse(
            animeJSON.titleUa,
            "$mainUrl/anime/$animeId",
            tvType
        ) {
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
        val targetEpisode = dataList[1].toIntOrNull() ?: return false

        val translationsJson =
            fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false

        val translations =
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

        translations.forEach { item ->
            item.player.forEach { player ->

                var episode: FundubEpisode? = null

                for (offset in 0..2000 step 100) {
                    val epUrl =
                        "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=${item.translation.id}"

                    val epJson = fetchJsonOrNull(epUrl) ?: continue

                    val eps = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                    } catch (_: Exception) { null }

                    if (eps.isNullOrEmpty()) break

                    episode = eps.firstOrNull { it.episode == targetEpisode }
                    if (episode != null) break
                }

                if (episode == null) return@forEach

                episode.fileUrl?.takeIf { it.isNotBlank() }?.let { fileUrl ->
                    M3u8Helper.generateM3u8(
                        source = "${item.translation.name} (${player.name})",
                        streamUrl = fileUrl,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)
                    return@forEach
                }

                val videoUrl = episode.videoUrl
                if (!videoUrl.isNullOrBlank()) {

                    val stream = if (videoUrl.contains("iframe")) {
                        getMoonFile(videoUrl)
                    } else videoUrl

                    if (stream.isNotBlank()) {
                        M3u8Helper.generateM3u8(
                            source = "${item.translation.name} (${player.name}) Moon",
                            streamUrl = stream,
                            referer = "https://moonanime.art/"
                        ).forEach(callback)
                    }
                }
            }
        }

        return true
    }

    private suspend fun getMoonFile(url: String): String {
        return try {
            val html = app.get(url).text
            Regex("""https://[^"']+\.m3u8[^"']*""")
                .find(html)?.value ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractIntFromString(string: String): Int? {
        return Regex("(\\d+)").findAll(string).lastOrNull()?.value?.toIntOrNull()
    }
}
