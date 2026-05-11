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
    override var lang = "uk"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"
    private val searchApi = "$apiUrl/search?text="

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private val listType = object : TypeToken<List<Results>>() {}.type

    private suspend fun getJson(url: String): String? {
        return try {
            val r = app.get(url, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to mainUrl
            )).text
            if (r.startsWith("{") || r.startsWith("[")) r else null
        } catch (_: Exception) {
            null
        }
    }

    private fun posterFix(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return try { posterApi.format(path) } catch (_: Exception) { null }
    }

    // ---------------- MAIN PAGE ----------------

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні",
        "$apiUrl/seasons" to "Сезон",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нові"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url =
            if (request.data.contains("%d")) request.data.format(page)
            else request.data

        val json = getJson(url) ?: return newHomePageResponse(request.name, emptyList())

        return try {

            val items = if (request.data.contains("seasons")) {
                Gson().fromJson(json, listType)
            } else {
                Gson().fromJson(json, NewAnimeModel::class.java).results
            }

            newHomePageResponse(request.name, items.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterFix(it.image?.preview)
                }
            })

        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ---------------- SEARCH ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val json = getJson(searchApi + query) ?: return emptyList()

        return try {
            Gson().fromJson(json, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterFix(it.image?.preview)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------- LOAD ----------------

    override suspend fun load(url: String): LoadResponse {

        val id = url.substringAfterLast("/").toIntOrNull()
            ?: throw Exception("Invalid id")

        val json = getJson("$apiUrl/$id")
            ?: throw Exception("No data")

        val anime = Gson().fromJson(json, AnimeInfoModel::class.java)

        val type = when {
            anime.type.contains("movie") -> TvType.AnimeMovie
            anime.type.contains("OVA") || anime.type.contains("ONA") -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = mutableListOf<Episode>()

        val transJson = getJson("$mainUrl/api/player/$id/translations")

        if (transJson != null) {
            try {
                val trans =
                    Gson().fromJson(transJson, TranslationsResponse::class.java).translations

                val seen = mutableSetOf<Int>()

                trans.forEach { t ->
                    t.player.forEach { p ->

                        val epJson = getJson(
                            "$mainUrl/api/player/$id/episodes?take=100&skip=0&playerId=${p.id}&translationId=${t.translation.id}"
                        ) ?: return@forEach

                        val eps = try {
                            Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                        } catch (_: Exception) { null } ?: return@forEach

                        eps.forEach { ep ->
                            if (seen.add(ep.episode)) {

                                episodes.add(
                                    newEpisode("$id, ${ep.episode}, ${ep.id}") {
                                        name = "Episode ${ep.episode}"
                                        episode = ep.episode
                                        posterUrl = ep.poster
                                        data = "$id, ${ep.episode}, ${ep.id}"
                                    }
                                )
                            }
                        }
                    }
                }

                episodes.sortBy { it.episode }

            } catch (_: Exception) {}
        }

        return newAnimeLoadResponse(anime.titleUa, "$mainUrl/anime/$id", type) {
            posterUrl = posterFix(anime.image?.preview)
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

    // ---------------- LINKS ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split(", ")
        if (parts.size < 2) return false

        val id = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false

        val transJson = getJson("$mainUrl/api/player/$id/translations") ?: return false

        val trans =
            Gson().fromJson(transJson, TranslationsResponse::class.java).translations

        var success = false

        trans.forEach { t ->
            t.player.forEach { p ->

                val epJson = getJson(
                    "$mainUrl/api/player/$id/episodes?take=100&skip=0&playerId=${p.id}&translationId=${t.translation.id}"
                ) ?: return@forEach

                val eps = try {
                    Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                } catch (_: Exception) { null } ?: return@forEach

                val ep = eps.firstOrNull { it.episode == epNum } ?: return@forEach

                // ===== MOON FIRST =====
                val moon = ep.videoUrl
                if (!moon.isNullOrBlank() && moon.contains("moonanime.art")) {

                    val url = if (moon.contains("iframe")) getMoon(moon) else moon

                    if (!url.isNullOrBlank() && url.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = "Moon ${t.translation.name}",
                            streamUrl = url,
                            referer = "https://moonanime.art/"
                        ).forEach(callback)

                        success = true
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

                    success = true
                    return@forEach
                }
            }
        }

        return success
    }

    private suspend fun getMoon(url: String): String {
        return try {
            val html = app.get(url).text
            Regex("""https://[^"']+\.m3u8[^"']*""")
                .find(html)?.value ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
