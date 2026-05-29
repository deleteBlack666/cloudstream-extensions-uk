package com.lagradost

import android.util.Log
import com.google.gson.Gson

import com.google.gson.annotations.SerializedName
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

    private val TAG = "ANIMEON"

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$mainUrl/api/anime?search="
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private data class SearchApiResponse(
        @SerializedName("results") val results: List<Result>,
        @SerializedName("totalCount") val totalCount: Int? = null,
    )

    private data class RedirectResponse(
        @SerializedName("moved") val moved: Boolean? = null,
        @SerializedName("redirectTo") val redirectTo: String? = null,
        @SerializedName("slug") val slug: String? = null,
    )

    private data class EpisodeSource(
        val translationName: String,
        val playerName: String,
        val videoUrl: String?,
        val fileUrl: String?,
    )

    private data class DirectPlayerResponse(
        @SerializedName("videoUrl") val videoUrl: String? = null,
        @SerializedName("fileUrl") val fileUrl: String? = null,
    )

    private data class FranchiseItem(
        @SerializedName("id") val id: Int,
        @SerializedName("slug") val slug: String?,
        @SerializedName("titleUa") val titleUa: String,
        @SerializedName("type") val type: String?,
        @SerializedName("image") val image: Image?,
        @SerializedName("releaseDate") val releaseDate: String?,
    )

    private fun fixExtractorLink(link: ExtractorLink, sourceName: String): ExtractorLink {
        val cleanQuality = when {
            link.url.contains("/1080/") -> 1080
            link.url.contains("/720/") -> 720
            link.url.contains("/480/") -> 480
            link.url.contains("/360/") -> 360
            else -> when (link.quality) {
                in 900..1150 -> 1080
                in 600..899 -> 720
                in 400..599 -> 480
                in 240..399 -> 360
                else -> link.quality
            }
        }
        
        // Зберігаємо оригінальну назву якості, якщо вона є, або додаємо суфікс розширення
        val qualitySuffix = if (cleanQuality > 0) " ${cleanQuality}p" else ""
        val finalName = if (link.name.contains("p") && link.name != "M3u8") link.name else "$sourceName$qualitySuffix"

        return ExtractorLink(
            source = link.source,
            name = finalName,
            url = link.url,
            referer = link.referer,
            quality = cleanQuality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
    }

    private suspend fun buildFranchise(animeId: Int): List<SearchResponse> {
        val json = fetchJsonOrNull("$mainUrl/api/franchise/full/$animeId") ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FranchiseItem>>() {}.type
            val items = Gson().fromJson<List<FranchiseItem>>(json, type)
            items.filter { it.id != animeId }.map { item ->
                newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                    this.posterUrl = item.image?.preview?.let { posterApi.format(it) }
                }
            }
        } catch (e: Exception) { emptyList() }
    }

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

    private suspend fun fetchJsonWithRetry(url: String, retries: Int = 3): String? {
        repeat(retries) {
            val result = fetchJsonOrNull(url)
            if (result != null) return result
        }
        return null
    }

    private suspend fun resolveAnimeApiUrl(animeId: Int): String {
        val initial = fetchJsonOrNull("$apiUrl/$animeId") ?: return "$apiUrl/$apiUrl/$animeId"
        return try {
            val redirect = Gson().fromJson(initial, RedirectResponse::class.java)
            if (redirect?.moved == true && !redirect.slug.isNullOrEmpty()) {
                "$apiUrl/${redirect.slug}"
            } else {
                "$apiUrl/$animeId"
            }
        } catch (e: Exception) {
            "$apiUrl/$animeId"
        }
    }

    private suspend fun getAshdiPoster(videoUrl: String?): String? {
        if (videoUrl.isNullOrEmpty()) return null
        if (!videoUrl.contains("ashdi.vip")) return null
        val url = if (videoUrl.contains("?")) videoUrl else "$videoUrl?player=animeon.club"
        return try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )).text

            val posterRegex = Regex("""poster:\s*["']((?:https?:)?//[^"']+)["']""")
            val raw = posterRegex.find(html)?.groupValues?.get(1)
            if (!raw.isNullOrEmpty()) {
                return if (raw.startsWith("http")) raw else "https:$raw"
            }

            val screenRegex = Regex("""((?:https?:)?//[^"'\s]+screen\.jpg)""")
            val screenMatch = screenRegex.find(html)?.groupValues?.get(1) ?: return null
            if (screenMatch.startsWith("http")) screenMatch else "https:$screenMatch"
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Популярні аніме") {
            if (page != 1) return newHomePageResponse(request.name, emptyList())
            val currentDate = java.text.SimpleDateFormat("EEE MMM dd yyyy", java.util.Locale.ENGLISH).format(java.util.Date())
            val jsonText = fetchJsonOrNull("${request.data}$currentDate?withView=false") ?: return newHomePageResponse(request.name, emptyList())
            val parsedJSON = Gson().fromJson<List<Results>>(jsonText, listResults)
            return newHomePageResponse(request.name, parsedJSON.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                }
            })
        }
        if (request.data.contains("seasons") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(if (request.data.contains("%d")) request.data.format(page) else request.data) ?: return newHomePageResponse(request.name, emptyList())
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
        val id = query.toIntOrNull()
        if (id != null) {
            val animeById = searchById(id)
            if (animeById != null) return listOf(animeById)
        }
        val url = "$searchApi${query}"
        val jsonText = fetchJsonOrNull(url) ?: return emptyList()
        return try {
            val response = Gson().fromJson(jsonText, SearchApiResponse::class.java)
            response.results.map { result ->
                newAnimeSearchResponse(result.titleUa, "anime/${result.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(result.image.preview)
                    addDubStatus(isDub = true, result.episodes)
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    private suspend fun searchById(id: Int): SearchResponse? {
        val realUrl = resolveAnimeApiUrl(id)
        val jsonText = fetchJsonOrNull(realUrl) ?: return null
        val anime = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { return null }
        return newAnimeSearchResponse(anime.titleUa, "anime/${anime.id}", TvType.Anime) {
            this.posterUrl = posterApi.format(anime.image.preview)
            addDubStatus(isDub = true, anime.episodes)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "LOAD_START: Заходження на сторінку тайтлу: $url")
        val animeId = url.substringAfterLast("/").substringBefore("-").toIntOrNull()
            ?: throw Exception("Invalid anime ID in URL: $url")

        Log.d(TAG, "LOAD_START: Визначено ID аніме = $animeId")
        val realApiUrl = resolveAnimeApiUrl(animeId)
        val jsonText = fetchJsonOrNull(realApiUrl) ?: throw Exception("Failed to load anime $animeId")
        val animeJSON = Gson().fromJson(jsonText, AnimeInfoModel::class.java)
            ?: throw Exception("Failed to parse anime $animeId")

        val posterUrl = animeJSON.image?.preview?.let { posterApi.format(it) } ?: ""
        val genres = animeJSON.genres?.map { it.nameUa } ?: emptyList()

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
        val translationsUrl = "$mainUrl/api/player/$animeId/translations"
        Log.d(TAG, "LOAD_EPISODES: Запит перекладів з: $translationsUrl")
        val translationsJson = fetchJsonOrNull(translationsUrl)
        
        if (translationsJson != null) {
            try {
                val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
                Log.d(TAG, "LOAD_EPISODES: Знайдено перекладів (озвучок/сабів) = ${translations.size}")
                val episodeSources = mutableMapOf<Int, MutableList<EpisodeSource>>()
                val episodePosters = mutableMapOf<Int, String?>()

                for (translation in translations) {
                    val translationId = translation.translation.id
                    Log.d(TAG, "LOAD_EPISODES: Обробка перекладу [${translation.translation.name}], плеєрів = ${translation.player.size}")
                    for (player in translation.player) {
                        val collected = mutableListOf<FundubEpisode>()
                        val seenIds = mutableSetOf<Int>()
                        val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"

                        val epJsonMinus1 = fetchJsonOrNull("$baseUrl&skip=-1")
                        if (epJsonMinus1 != null) {
                            val eps = try { Gson().fromJson(epJsonMinus1, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                            eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                        }

                        val maxSkip = if (player.episodesCount > 0)
                            (player.episodesCount / 100 + 1) * 100
                        else 11000
                        var skip = 0
                        while (skip <= maxSkip) {
                            val epJson = fetchJsonOrNull("$baseUrl&skip=$skip") ?: break
                            val eps = try { Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                            if (eps.isNullOrEmpty()) break
                            val newEps = eps.filter { seenIds.add(it.id) }
                            collected.addAll(newEps)
                            if (eps.size < 100) break
                            skip += 100
                        } 
                        
                        Log.d(TAG, "LOAD_EPISODES: Плеєр [${player.name}] зібрав ${collected.size} епізодів")
                        for (ep in collected) {
                            episodeSources.getOrPut(ep.episode) { mutableListOf() }.add(
                                EpisodeSource(
                                    translationName = translation.translation.name,
                                    playerName = player.name,
                                    videoUrl = ep.videoUrl,
                                    fileUrl = ep.fileUrl,
                                )
                            )
                            if (!ep.poster.isNullOrEmpty() && !episodePosters.containsKey(ep.episode)) {
                                val restricted = ep.poster.contains("mooncdn.net")
                                if (!restricted) {
                                    episodePosters[ep.episode] = ep.poster
                                }
                            }
                        }
                    }
                }

                episodeSources.keys.sorted().forEach { epNum ->
                    val sources = episodeSources[epNum] ?: return@forEach
                    var epPoster: String? = episodePosters[epNum]

                    if (epPoster.isNullOrEmpty()) {
                        val ashdiSource = sources.firstOrNull {
                            it.playerName.contains("Ashdi", ignoreCase = true) && !it.videoUrl.isNullOrEmpty()
                        }
                        if (ashdiSource != null) epPoster = getAshdiPoster(ashdiSource.videoUrl!!)
                    }

                    val dataJson = Gson().toJson(sources)
                    episodes.add(newEpisode(dataJson) {
                        this.name = "Епізод $epNum"
                        this.posterUrl = epPoster
                        this.episode = epNum
                        this.data = dataJson
                    })
                }
                Log.d(TAG, "LOAD_EPISODES: Успішно згенеровано ${episodes.size} унікальних епізодів для Cloudstream")
            } catch (e: Exception) { 
                Log.e(TAG, "LOAD_EPISODES: Помилка збору епізодів", e)
            }
        } else {
            Log.e(TAG, "LOAD_EPISODES: Не вдалося отримати JSON перекладів (null)")
        }

        val franchise = buildFranchise(animeId)

        return if (tvType == TvType.Anime || tvType == TvType.OVA) {
            newAnimeLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType) {
                this.posterUrl = posterUrl
                this.engName = animeJSON.titleEn
                this.tags = genres
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.showStatus = showStatus
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.score = Score.from10(animeJSON.rating)
                addEpisodes(DubStatus.Dubbed, episodes)
                addMalId(animeJSON.malId.toIntOrNull())
                this.recommendations = franchise
            }
        } else {
            val backgroundImage = if (animeJSON.backgroundImage.isNullOrBlank()) posterUrl else animeJSON.backgroundImage
            newMovieLoadResponse(animeJSON.titleUa, "$mainUrl/anime/$animeId", tvType, animeId.toString()) {
                this.posterUrl = posterUrl
                this.tags = genres
                this.plot = animeJSON.description
                addTrailer(animeJSON.trailer)
                this.duration = extractIntFromString(animeJSON.episodeTime)
                this.year = animeJSON.releaseDate?.toIntOrNull()
                this.backgroundPosterUrl = backgroundImage
                this.score = Score.from10(animeJSON.rating)
                addMalId(animeJSON.malId.toIntOrNull())
                this.recommendations = franchise
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "LOAD_LINKS: Запущено процес відтворення")
        Log.d(TAG, "LOAD_LINKS: data = $data")
        val animeId = data.trim().toIntOrNull()
        if (animeId != null) {
            Log.d(TAG, "LOAD_LINKS: Виявлено ID фільму = $animeId")
            return loadMovieLinks(animeId, callback)
        }

        val sourceType = object : TypeToken<List<EpisodeSource>>() {}.type
        val sources: List<EpisodeSource> = try {
            Gson().fromJson(data, sourceType)
        } catch (e: Exception) {
            Log.e(TAG, "LOAD_LINKS: Помилка парсингу JSON джерел епізоду", e)
            return false
        }

        if (sources.isEmpty()) {
            Log.d(TAG, "LOAD_LINKS: Список джерел порожній")
            return false
        }
        var foundAny = false

        val moonHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.art/",
            "Origin" to "https://moonanime.art"
        )

        for ((index, source) in sources.withIndex()) {
            val sourceName = "${source.translationName} (${source.playerName})"
            val isAshdi = source.playerName.contains("Ashdi", ignoreCase = true)
            val fileUrl = source.fileUrl
            val videoUrl = source.videoUrl

            Log.d(TAG, "LOAD_LINKS: Обробка джерела [$index]: $sourceName")

            try {
                if (isAshdi) {
                    if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                        processAshdiIframe(videoUrl, sourceName, isMovie = false, callback)
                        foundAny = true
                    } else if (!fileUrl.isNullOrEmpty()) {
                        Log.d(TAG, "LOAD_LINKS: Спроба запуску прямим M3u8 для Ashdi: $fileUrl")
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = fileUrl,
                            referer = "https://ashdi.vip"
                        )
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(it) }
                        else streams.forEach { callback(it) }
                        foundAny = true
                    }
                } else {
                    // Обробка Moon
                    if (!fileUrl.isNullOrEmpty()) {
                        Log.d(TAG, "LOAD_LINKS: Обробка Moon через fileUrl = $fileUrl")
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = fileUrl,
                            referer = "https://moonanime.art/",
                            headers = moonHeaders
                        )
                        val filtered = streams.dropLast(1)
                        if (filtered.isNotEmpty()) filtered.forEach { callback(it) }
                        else streams.forEach { callback(it) }
                        foundAny = true
                    } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                        if (videoUrl.contains("m3u8")) {
                            Log.d(TAG, "LOAD_LINKS: Прямий m3u8 у videoUrl = $videoUrl")
                            val streams = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = videoUrl,
                                referer = "https://moonanime.art/",
                                headers = moonHeaders
                            )
                            val filtered = streams.dropLast(1)
                            if (filtered.isNotEmpty()) filtered.forEach { callback(it) }
                            else streams.forEach { callback(it) }
                            foundAny = true
                        } else {
                            Log.d(TAG, "LOAD_LINKS: Виклик getMoonFile для $videoUrl")
                            val rawFile = getMoonFile(videoUrl)
                            Log.d(TAG, "LOAD_LINKS: getMoonFile повернув = $rawFile")
                            
                            if (rawFile.isNotEmpty()) {
                                if (rawFile.startsWith("[")) {
                                    val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
                                    qualityRegex.findAll(rawFile).forEach { match ->
                                        val qualityStr = match.groupValues[1]
                                        val qUrl = match.groupValues[2]
                                        val qualityInt = qualityStr.replace("p", "").toIntOrNull() ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                                        
                                        if (qUrl.contains(".m3u8")) {
                                            Log.d(TAG, "LOAD_LINKS: Парсинг m3u8 з масиву Moon: $qUrl")
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = qUrl,
                                                referer = "https://moonanime.art/",
                                                headers = moonHeaders
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) filtered.forEach { callback(it) }
                                            else streams.forEach { callback(it) }
                                        } else {
                                            callback(
                                                ExtractorLink(
                                                    source = name,
                                                    name = "$sourceName $qualityStr",
                                                    url = qUrl,
                                                    referer = "https://moonanime.art/",
                                                    quality = qualityInt,
                                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                                    headers = moonHeaders
                                                )
                                            )
                                        }
                                    }
                                } else if (rawFile.contains(".m3u8")) {
                                    Log.d(TAG, "LOAD_LINKS: Передача одиночного m3u8 в M3u8Helper з заголовками: $rawFile")
                                    val streams = try {
                                        M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = rawFile,
                                            referer = "https://moonanime.art/",
                                            headers = moonHeaders
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "LOAD_LINKS: Крах M3u8Helper для $rawFile", e)
                                        emptyList()
                                    }
                                    Log.d(TAG, "LOAD_LINKS: M3u8Helper розпарсив потоків = ${streams.size}")
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) filtered.forEach { callback(it) }
                                    else streams.forEach { callback(it) }
                                } else {
                                    callback(
                                        ExtractorLink(
                                            source = name,
                                            name = sourceName,
                                            url = rawFile,
                                            referer = "https://moonanime.art/",
                                            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                            headers = moonHeaders
                                        )
                                    )
                                }
                                foundAny = true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LOAD_LINKS: Помилка обробки джерела на індексі $index", e)
            }
        }

        Log.d(TAG, "LOAD_LINKS: Завершено. Результат успішності знайдення лінків = $foundAny")
        return foundAny
    }

    private suspend fun loadMovieLinks(
        animeId: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "MOVIE_LINKS: Завантаження лінків для фільму ID = $animeId")
        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        var foundAny = false

        val moonHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://moonanime.art/",
            "Origin" to "https://moonanime.art"
        )

        try {
            val translations = Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

            for (translation in translations) {
                val translationId = translation.translation.id
                for (player in translation.player) {
                    val collected = mutableListOf<FundubEpisode>()
                    val seenIds = mutableSetOf<Int>()
                    val baseUrl = "$mainUrl/api/player/$animeId/episodes?take=100&playerId=${player.id}&translationId=$translationId"
                    val epJsonMinus1 = fetchJsonWithRetry("$baseUrl&skip=-1")
                    if (epJsonMinus1 != null) {
                        val eps = try { Gson().fromJson(epJsonMinus1, PlayerEpisodes::class.java).episodes } catch (e: Exception) { null }
                        eps?.filter { it.episode <= 0 && seenIds.add(it.id) }?.let { collected.addAll(it) }
                    }
                    val maxSkip = if (player.episodesCount > 0)
                        (player.episodesCount / 100 + 1) * 100
                    else 11000
                    var skip = 0
                    while (skip <= maxSkip) {
                        val epJson = fetchJsonWithRetry("$baseUrl&skip=$skip") ?: break
                        val eps = try {
                            Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                        } catch (e: Exception) { null }
                        if (eps.isNullOrEmpty()) break
                        val newEps = eps.filter { seenIds.add(it.id) }
                        collected.addAll(newEps)
                        if (eps.size < 100) break
                        skip += 100
                    }

                    val sourceName = "${translation.translation.name} (${player.name})"
                    val isAshdi = player.name.contains("Ashdi", ignoreCase = true)

                    Log.d(TAG, "MOVIE_LINKS: Плеєр=${player.name}, Переклад=${translation.translation.name}, Зібрано епізодів=${collected.size}")

                    if (collected.isEmpty()) {
                        val directJson = fetchJsonOrNull("$mainUrl/api/player/${player.id}/${translation.translation.id}")
                        if (directJson != null) {
                            try {
                                val directSource = Gson().fromJson(directJson, DirectPlayerResponse::class.java)
                                val videoUrl = directSource.videoUrl
                                val fileUrl = directSource.fileUrl
                                if (!videoUrl.isNullOrEmpty() || !fileUrl.isNullOrEmpty()) {
                                    if (isAshdi) {
                                        if (!videoUrl.isNullOrEmpty() && videoUrl.contains("ashdi.vip")) {
                                            processAshdiIframe(videoUrl, sourceName, isMovie = true, callback)
                                            foundAny = true
                                        } else if (!fileUrl.isNullOrEmpty()) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = fileUrl,
                                                referer = "https://ashdi.vip"
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                            else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                            foundAny = true
                                        }
                                    } else {
                                        if (!fileUrl.isNullOrEmpty()) {
                                            val streams = M3u8Helper.generateM3u8(
                                                source = sourceName,
                                                streamUrl = fileUrl,
                                                referer = "https://moonanime.art/",
                                                headers = moonHeaders
                                            )
                                            val filtered = streams.dropLast(1)
                                            if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                            else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                            foundAny = true
                                        } else if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                                            if (videoUrl.contains("m3u8")) {
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = videoUrl,
                                                    referer = "https://moonanime.art/",
                                                    headers = moonHeaders
                                                )
                                                val filtered = streams.dropLast(1)
                                                if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                foundAny = true
                                            } else {
                                                val rawFile = getMoonFile(videoUrl)
                                                if (rawFile.isNotEmpty()) {
                                                    if (rawFile.startsWith("[")) {
                                                        val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
                                                        qualityRegex.findAll(rawFile).forEach { match ->
                                                            val qualityStr = match.groupValues[1]
                                                            val qUrl = match.groupValues[2]
                                                            val qualityInt = qualityStr.replace("p", "").toIntOrNull() ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                                                            
                                                            if (qUrl.contains(".m3u8")) {
                                                                val streams = M3u8Helper.generateM3u8(
                                                                    source = sourceName,
                                                                    streamUrl = qUrl,
                                                                    referer = "https://moonanime.art/",
                                                                    headers = moonHeaders
                                                                )
                                                                val filtered = streams.dropLast(1)
                                                                if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                                else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                            } else {
                                                                val directLink = ExtractorLink(
                                                                    source = name,
                                                                    name = "$sourceName $qualityStr",
                                                                    url = qUrl,
                                                                    referer = "https://moonanime.art/",
                                                                    quality = qualityInt,
                                                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                                                    headers = moonHeaders
                                                                )
                                                                callback(fixExtractorLink(directLink, sourceName))
                                                            }
                                                        }
                                                    } else if (rawFile.contains(".m3u8")) {
                                                        val streams = M3u8Helper.generateM3u8(
                                                            source = sourceName,
                                                            streamUrl = rawFile,
                                                            referer = "https://moonanime.art/",
                                                            headers = moonHeaders
                                                        )
                                                        val filtered = streams.dropLast(1)
                                                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                        else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                    } else {
                                                        val directLink = ExtractorLink(
                                                            source = name,
                                                            name = sourceName,
                                                            url = rawFile,
                                                            referer = "https://moonanime.art/",
                                                            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                                            headers = moonHeaders
                                                        )
                                                        callback(fixExtractorLink(directLink, sourceName))
                                                    }
                                                    foundAny = true
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) { }
                        }
                        continue
                    }

                    for (ep in collected) {
                        try {
                            if (isAshdi) {
                                if (!ep.videoUrl.isNullOrEmpty() && ep.videoUrl.contains("ashdi.vip")) {
                                    processAshdiIframe(ep.videoUrl, sourceName, isMovie = true, callback)
                                    foundAny = true
                                } else if (!ep.fileUrl.isNullOrEmpty()) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = ep.fileUrl,
                                        referer = "https://ashdi.vip"
                                    )
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                    else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                    foundAny = true
                                }
                            } else {
                                if (!ep.fileUrl.isNullOrEmpty()) {
                                    val streams = M3u8Helper.generateM3u8(
                                        source = sourceName,
                                        streamUrl = ep.fileUrl,
                                        referer = "https://moonanime.art/",
                                        headers = moonHeaders
                                    )
                                    val filtered = streams.dropLast(1)
                                    if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                    else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                    foundAny = true
                                } else if (!ep.videoUrl.isNullOrEmpty() && ep.videoUrl.contains("moonanime.art")) {
                                    if (ep.videoUrl.contains("m3u8")) {
                                        val streams = M3u8Helper.generateM3u8(
                                            source = sourceName,
                                            streamUrl = ep.videoUrl,
                                            referer = "https://moonanime.art/",
                                            headers = moonHeaders
                                        )
                                        val filtered = streams.dropLast(1)
                                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                        else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                        foundAny = true
                                    } else {
                                        Log.d(TAG, "MOVIE_LINKS: Виклик getMoonFile для фільму: ${ep.videoUrl}")
                                        val rawFile = getMoonFile(ep.videoUrl)
                                        Log.d(TAG, "MOVIE_LINKS: Результат фільму getMoonFile = $rawFile")
                                        if (rawFile.isNotEmpty()) {
                                            if (rawFile.startsWith("[")) {
                                                val qualityRegex = Regex("""\[(\d+p)\](https?://[^\s,]+)""")
                                                qualityRegex.findAll(rawFile).forEach { match ->
                                                    val qualityStr = match.groupValues[1]
                                                    val qUrl = match.groupValues[2]
                                                    val qualityInt = qualityStr.replace("p", "").toIntOrNull() ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                                                    
                                                    if (qUrl.contains(".m3u8")) {
                                                        val streams = M3u8Helper.generateM3u8(
                                                            source = sourceName,
                                                            streamUrl = qUrl,
                                                            referer = "https://moonanime.art/",
                                                            headers = moonHeaders
                                                        )
                                                        val filtered = streams.dropLast(1)
                                                        if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                        else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                    } else {
                                                        val directLink = ExtractorLink(
                                                            source = name,
                                                            name = "$sourceName $qualityStr",
                                                            url = qUrl,
                                                            referer = "https://moonanime.art/",
                                                            quality = qualityInt,
                                                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                                            headers = moonHeaders
                                                        )
                                                        callback(fixExtractorLink(directLink, sourceName))
                                                    }
                                                }
                                            } else if (rawFile.contains(".m3u8")) {
                                                val streams = M3u8Helper.generateM3u8(
                                                    source = sourceName,
                                                    streamUrl = rawFile,
                                                    referer = "https://moonanime.art/",
                                                    headers = moonHeaders
                                                )
                                                val filtered = streams.dropLast(1)
                                                if (filtered.isNotEmpty()) filtered.forEach { callback(fixExtractorLink(it, sourceName)) }
                                                else streams.forEach { callback(fixExtractorLink(it, sourceName)) }
                                            } else {
                                                val directLink = ExtractorLink(
                                                    source = name,
                                                    name = sourceName,
                                                    url = rawFile,
                                                    referer = "https://moonanime.art/",
                                                    quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                                                    headers = moonHeaders
                                                )
                                                callback(fixExtractorLink(directLink, sourceName))
                                            }
                                            foundAny = true
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: Exception) { }

        return foundAny
    }

    private suspend fun processAshdiIframe(iframeUrl: String, sourceName: String, isMovie: Boolean, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanUrl = iframeUrl
                .replace(Regex("""\?season=null\?"""), "?")
                .replace(Regex("""\?season=null$"""), "")
            val url = if (cleanUrl.contains("?")) cleanUrl else "$cleanUrl?player=animeon.club"
            val html = app.get(url, headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7"
            )).text

            val fileIndex = html.indexOf("file:'")
            if (fileIndex != -1) {
                val urlStart = fileIndex + 6
                val urlEnd = html.indexOf('\'', urlStart)
                if (urlEnd != -1) {
                    val masterUrl = html.substring(urlStart, urlEnd)
                    if (masterUrl.isNotEmpty() && masterUrl.endsWith(".m3u8")) {
                        val streams = M3u8Helper.generateM3u8(
                            source = sourceName,
                            streamUrl = masterUrl,
                            referer = "https://ashdi.vip/"
                        )
                        val filtered = streams.dropLast(1)
                        val finalStreams = if (filtered.isNotEmpty()) filtered else streams
                        finalStreams.forEach { link ->
                            if (isMovie) callback(fixExtractorLink(link, sourceName))
                            else callback(link)
                        }
                    }
                }
            }
        } catch (e: Exception) { }
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

    private fun moonOuterDecode(base64Blob: String): String {
        return try {
            val raw = android.util.Base64.decode(base64Blob, android.util.Base64.DEFAULT)
            if (raw.size < 33) return ""

            val state0 = raw[0].toInt() and 0xFF
            val key = raw.sliceArray(1 until 33)
            val data = raw.sliceArray(33 until raw.size)

            val result = StringBuilder()
            var state = state0
            for (i in data.indices) {
                val d = data[i].toInt() and 0xFF
                val k = key[i % 32].toInt() and 0xFF
                val dec = d xor k xor state
                result.append(dec.toChar())
                
                state = (d + k) and 0xFF
            }
            result.toString()
        } catch (e: Exception) { "" }
    }
    
    private suspend fun getMoonFile(iframeUrl: String): String {
        val cleanUrl = iframeUrl
            .replace(Regex("[?&]player=[^&]*"), "")
            .replace("?&", "?")
            .trimEnd('?', '&')

        Log.d(TAG, "GET_MOON_FILE: Очищений iframe URL = $cleanUrl")
        val html = app.get(cleanUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://animeon.club/"
        )).text

        val atobRegex = Regex("""atob\(["']([^"']+)["']\)""")
        val atobMatch = atobRegex.find(html)?.groupValues?.get(1)
        if (atobMatch.isNullOrEmpty()) {
            Log.e(TAG, "GET_MOON_FILE: Не знайдено блок atob(...) в HTML")
            return ""
        }
        
        val decodedJs = moonOuterDecode(atobMatch)
        if (decodedJs.isEmpty()) {
            Log.e(TAG, "GET_MOON_FILE: moonOuterDecode повернув порожній рядок JS")
            return ""
        }

        val keyRegex = Regex("""var\s+k\s*=\s*["']([^"']+)["']""")
        val xorKey = keyRegex.find(decodedJs)?.groupValues?.get(1)
        if (xorKey.isNullOrEmpty()) {
            Log.e(TAG, "GET_MOON_FILE: Не знайдено xor-ключ 'var k'")
            return ""
        }

        val encodedRegex = Regex("""_0xd\(["']([^"']+)["']\)""")
        val matches = encodedRegex.findAll(decodedJs).toList()
        Log.d(TAG, "GET_MOON_FILE: Знайдено зашифрованих рядків _0xd = ${matches.size}")

        for ((idx, match) in matches.withIndex()) {
            val decoded = moonDecrypt(match.groupValues[1], xorKey)
            if (decoded.contains(".m3u8") || decoded.contains(".webm") || decoded.contains(".mp4")) {
                Log.d(TAG, "GET_MOON_FILE: Знайдено медіа-рядок на індексі $idx → $decoded")
                return decoded
            }
        }

        Log.e(TAG, "GET_MOON_FILE: Медіа-файлів не виявлено серед розшифрованих рядків JS")
        return ""
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        if (value.value[0].toString() == "0") return value.value.drop(1).toIntOrNull()
        return value.value.toIntOrNull()
    }
}
