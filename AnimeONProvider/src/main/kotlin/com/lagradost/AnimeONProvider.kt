package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.M3u8Helper

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
            val r = app.get(url, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to userAgent
            )).text

            if (r.trim().startsWith("{") || r.trim().startsWith("[")) r else null
        } catch (_: Exception) { null }
    }

    private fun safePoster(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try { posterApi.format(path) } catch (_: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val jsonText = fetchJsonOrNull(
            if (request.data.contains("%d")) request.data.format(page)
            else request.data
        ) ?: return newHomePageResponse(request.name, emptyList())

        return try {

            val parsed = if (request.data.contains("seasons")) {
                Gson().fromJson(jsonText, listResults)
            } else {
                Gson().fromJson(jsonText, NewAnimeModel::class.java).results
            }

            newHomePageResponse(
                request.name,
                parsed.map {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        this.posterUrl = safePoster(it.image?.preview)
                    }
                }
            )

        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + query) ?: return emptyList()

        return try {
            Gson().fromJson(jsonText, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = safePoster(it.image?.preview)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val animeId = url.substringAfterLast("/").toIntOrNull()
            ?: throw Exception("Invalid ID")

        val jsonText = fetchJsonOrNull("$apiUrl/$animeId")
            ?: throw Exception("No anime data")

        val anime = Gson().fromJson(jsonText, AnimeInfoModel::class.java)

        val type = when {
            anime.type.contains("movie") -> TvType.AnimeMovie
            anime.type.contains("OVA") || anime.type.contains("ONA") -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = mutableListOf<Episode>()

        val transJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (transJson != null) {
            try {
                val trans = Gson().fromJson(transJson, TranslationsResponse::class.java).translations

                val seen = mutableSetOf<Int>()

                trans.forEach { t ->
                    t.player.forEach { p ->

                        for (skip in 0..2000 step 100) {
                            val epJson = fetchJsonOrNull(
                                "$mainUrl/api/player/$animeId/episodes?take=100&skip=$skip&playerId=${p.id}&translationId=${t.translation.id}"
                            ) ?: continue

                            val eps = try {
                                Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                            } catch (_: Exception) { null } ?: continue

                            eps.forEach { ep ->
                                if (seen.add(ep.episode)) {

                                    val poster = safePoster(ep.poster)

                                    episodes.add(
                                        newEpisode("$animeId, ${ep.episode}, ${ep.id}") {
                                            name = "Episode ${ep.episode}"
                                            this.posterUrl = poster
                                            episode = ep.episode
                                            data = "$animeId, ${ep.episode}, ${ep.id}"
                                        }
                                    )
                                }
                            }

                            if (eps.size < 100) break
                        }
                    }
                }

                episodes.sortBy { it.episode }

            } catch (_: Exception) {}
        }

        return newAnimeLoadResponse(anime.titleUa, "$mainUrl/anime/$animeId", type) {
            posterUrl = safePoster(anime.image?.preview)
            engName = anime.titleEn
            tags = anime.genres.map { it.nameUa }
            plot = anime.description
            addTrailer(anime.trailer)
            year = anime.releaseDate.toIntOrNull()
            score = Score.from10(anime.rating)
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(anime.malId.toIntOrNull())
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

        val animeId = parts[0]
        val episodeNum = parts[1].toIntOrNull() ?: return false

        val transJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false

        val trans = Gson().fromJson(transJson, TranslationsResponse::class.java).translations

        var foundAny = false

        trans.forEach { t ->
            t.player.forEach { p ->

                val epJson = fetchJsonOrNull(
                    "$mainUrl/api/player/$animeId/episodes?take=100&skip=0&playerId=${p.id}&translationId=${t.translation.id}"
                ) ?: return@forEach

                val eps = try {
                    Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                } catch (_: Exception) { null } ?: return@forEach

                val ep = eps.firstOrNull { it.episode == episodeNum } ?: return@forEach

                // ===== MOON PRIORITY =====
                val moonUrl = ep.videoUrl
                if (!moonUrl.isNullOrBlank() && moonUrl.contains("moonanime.art")) {
                    val final = if (moonUrl.contains("iframe")) getMoonFile(moonUrl) else moonUrl

                    if (!final.isNullOrBlank() && final.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = "Moon ${t.translation.name}",
                            streamUrl = final,
                            referer = "https://moonanime.art/"
                        ).forEach(callback)

                        foundAny = true
                        return@forEach
                    }
                }

                // ===== ASHDI FALLBACK =====
                val file = ep.fileUrl
                if (!file.isNullOrBlank() && file.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = "Ashdi ${t.translation.name}",
                        streamUrl = file,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)

                    foundAny = true
                    return@forEach
                }
            }
        }

        return foundAny
    }

    private suspend fun getMoonFile(url: String): String {
        return try {
            val html = app.get(url).text
            Regex("""https://[^"']+\.m3u8[^"']*""")
                .find(html)?.value ?: ""
        } catch (_: Exception) { "" }
    }
}
