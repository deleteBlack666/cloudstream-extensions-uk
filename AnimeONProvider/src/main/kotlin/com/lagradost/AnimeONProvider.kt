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

    // Тимчасові класи для головної сторінки (якщо старі моделі не працюють)
    data class TempAnimeItem(val id: Int, val titleUa: String, val image: TempImage?)
    data class TempImage(val preview: String)

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
        val url = request.data.format(page)
        val json = fetchJsonOrNull(url) ?: return newHomePageResponse(request.name, emptyList())
        val list = try {
            // Спроба як об'єкт NewAnimeModel (якщо є)
            val model = Gson().fromJson(json, NewAnimeModel::class.java)
            model.results?.mapNotNull { it?.let { r ->
                newAnimeSearchResponse(r.titleUa, "anime/${r.id}", TvType.Anime) {
                    posterUrl = posterApi.format(r.image?.preview ?: "")
                }
            } } ?: emptyList()
        } catch (e: Exception) {
            try {
                // Спроба як масив
                val type = object : TypeToken<Array<TempAnimeItem>>() {}.type
                Gson().fromJson<Array<TempAnimeItem>>(json, type).map { item ->
                    newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                        posterUrl = posterApi.format(item.image?.preview ?: "")
                    }
                }
            } catch (e2: Exception) {
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
            throw Exception("JSON parse error")
        }
        val animeId = anime.id ?: throw Exception("Anime ID missing")

        // ---- Отримуємо переклади (translations) замість fundubs ----
        val translationsUrl = "$mainUrl/api/player/translations/$animeId"
        val translationsJson = fetchJsonOrNull(translationsUrl) ?: throw Exception("No translations")
        val translationsResponse = try {
            Gson().fromJson(translationsJson, TranslationResponse::class.java)
        } catch (e: Exception) {
            throw Exception("Translations parse error")
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        // Беремо перший переклад, який має хоча б одного плеєра
        val translation = translationsResponse.translations.firstOrNull { it.player.isNotEmpty() }
            ?: throw Exception("No translation with player")

        // Для кожного плеєра (можна взяти перший, або всі – але зазвичай перший)
        val player = translation.player.firstOrNull() ?: throw Exception("No player")
        val playerId = player.id
        val translationId = translation.id

        // Отримуємо серії / сезони
        val episodesUrl = "$mainUrl/api/player/episodes?playerId=$playerId&translationId=$translationId"
        val episodesJson = fetchJsonOrNull(episodesUrl) ?: throw Exception("No episodes data")
        val playerJson = try {
            Gson().fromJson(episodesJson, PlayerJson::class.java)
        } catch (e: Exception) {
            throw Exception("Episodes parse error")
        }

        // Проходимо по сезонах та епізодах
        playerJson.folder.forEachIndexed { seasonIndex, season ->
            season.folder.forEachIndexed { epIndex, episode ->
                episodes.add(
                    newEpisode("$animeId|$playerId|$translationId|$seasonIndex|$epIndex") {
                        name = "${season.title} - Серія ${episode.title}"
                        posterUrl = episode.poster
                        this.episode = epIndex + 1
                        data = episode.file // пряме посилання на відео
                    }
                )
            }
        }

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

        return newAnimeLoadResponse(anime.titleUa ?: "Без назви", "$mainUrl/anime/$animeId", tvType) {
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
        // data містить або episode.file (пряме посилання), або складений ключ
        if (data.startsWith("http")) {
            // Пряме посилання на відео (наприклад, .m3u8)
            val m3uUrl = getM3U(data) // або одразу data, якщо це m3u8
            if (m3uUrl.isNotEmpty()) {
                M3u8Helper.generateM3u8("AnimeON", m3uUrl, mainUrl).dropLast(1).forEach(callback)
                return true
            }
        } else {
            // Якщо зберігали складений ключ (наприклад, "animeId|playerId|translationId|seasonIndex|epIndex")
            val parts = data.split("|")
            if (parts.size == 5) {
                val animeId = parts[0]
                val playerId = parts[1]
                val translationId = parts[2]
                val seasonIndex = parts[3].toIntOrNull() ?: return false
                val epIndex = parts[4].toIntOrNull() ?: return false

                // Отримуємо список серій знову (або краще кешувати, але для простоти знову завантажимо)
                val episodesUrl = "$mainUrl/api/player/episodes?playerId=$playerId&translationId=$translationId"
                val episodesJson = fetchJsonOrNull(episodesUrl) ?: return false
                val playerJson = try {
                    Gson().fromJson(episodesJson, PlayerJson::class.java)
                } catch (e: Exception) {
                    return false
                }
                val season = playerJson.folder.getOrNull(seasonIndex)
                val episode = season?.folder?.getOrNull(epIndex)
                val videoUrl = episode?.file ?: return false
                val m3uUrl = getM3U(videoUrl)
                if (m3uUrl.isNotEmpty()) {
                    M3u8Helper.generateM3u8("AnimeON", m3uUrl, mainUrl).dropLast(1).forEach(callback)
                    return true
                }
            }
        }
        return false
    }

    private suspend fun getM3U(url: String): String {
        // Якщо url вже є .m3u8, повертаємо його
        if (url.endsWith(".m3u8") || url.contains(".m3u8?")) return url
        // Інакше шукаємо file: в script
        val html = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).document.select("script").html()
        return if (html.contains("cloudflare", ignoreCase = true)) "" else fileRegex.find(html)?.groupValues?.get(1) ?: ""
    }
}
