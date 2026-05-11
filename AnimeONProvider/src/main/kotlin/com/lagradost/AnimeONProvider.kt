package com.lagradost

import android.util.Base64
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
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/stats/anime/" to "Популярні аніме",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове аніме на сайті",
    )

    private val listResults = object : TypeToken<List<Results>>() {}.type

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).text

            if (res.startsWith("{") || res.startsWith("[")) res else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- MAIN PAGE ----------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        if (request.name == "Популярні аніме" && page != 1)
            return newHomePageResponse(request.name, emptyList())

        val url = if (request.data.contains("%d"))
            request.data.format(page)
        else request.data

        val json = fetchJsonOrNull(url) ?: return newHomePageResponse(request.name, emptyList())

        return try {

            val list = if (request.data.contains("seasons")) {
                Gson().fromJson<List<Results>>(json, listResults)
            } else {
                Gson().fromJson(json, NewAnimeModel::class.java).results
            }

            newHomePageResponse(
                request.name,
                list.map {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        this.posterUrl = posterApi.format(it.image.preview)
                    }
                }
            )

        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ---------------- SEARCH ----------------

    override suspend fun search(query: String): List<SearchResponse> {
        val json = fetchJsonOrNull(searchApi + query) ?: return emptyList()

        return try {
            Gson().fromJson(json, SearchModel::class.java).result.map {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    this.posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(true, it.episodes)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String) = search(query)

    // ---------------- LOAD ----------------

    override suspend fun load(url: String): LoadResponse {

        val animeId = url.substringAfterLast("/").substringBefore("-").toInt()
        val json = fetchJsonOrNull("$apiUrl/$animeId")
            ?: throw Exception("No data")

        val anime = Gson().fromJson(json, AnimeInfoModel::class.java)

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<Int>()

        val translationsJson =
            fetchJsonOrNull("$mainUrl/api/player/$animeId/translations")

        if (translationsJson != null) {

            try {
                val translations =
                    Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations

                for (t in translations) {
                    for (player in t.player) {

                        if (player.id == null) continue

                        val epJson = fetchJsonOrNull(
                            "$mainUrl/api/player/$animeId/episodes?take=500&skip=0&playerId=${player.id}&translationId=${t.translation.id}"
                        ) ?: continue

                        val eps =
                            try {
                                Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                            } catch (_: Exception) {
                                null
                            } ?: continue

                        for (ep in eps) {

                            if (ep.episode == null) continue
                            if (!seen.add(ep.episode)) continue

                            episodes.add(
                                newEpisode("$animeId,${ep.episode},${ep.id}") {
                                    this.name = "Епізод ${ep.episode}"
                                    this.episode = ep.episode
                                    this.data = "$animeId,${ep.episode},${ep.id}"
                                    this.posterUrl = ep.poster.takeIf { !it.isNullOrBlank() }
                                }
                            )
                        }
                    }
                }

                episodes.sortBy { it.episode }

            } catch (_: Exception) {}
        }

        return newAnimeLoadResponse(
            anime.titleUa,
            "$mainUrl/anime/$animeId",
            TvType.Anime
        ) {

            this.posterUrl = posterApi.format(anime.image.preview)
            this.engName = anime.titleEn
            this.tags = anime.genres.map { it.nameUa }
            this.plot = anime.description
            addTrailer(anime.trailer)
            this.year = anime.releaseDate.toIntOrNull()
            this.score = Score.from10(anime.rating)
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(anime.malId.toIntOrNull())
        }
    }

    // ---------------- LINKS (FIXED) ----------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val split = data.split(",")
        if (split.size < 3) return false

        val animeId = split[0]
        val epId = split[2].toIntOrNull() ?: return false

        val epJson =
            fetchJsonOrNull("$mainUrl/api/player/$epId/episode")
                ?: return false

        val episode =
            try {
                Gson().fromJson(epJson, FundubEpisode::class.java)
            } catch (_: Exception) {
                return false
            }

        // ---------------- SKIP BROKEN SOURCES ----------------
        fun isBad(url: String?) =
            url.isNullOrBlank() || url == "null" || url.length < 10

        // ---------------- MOON FIRST ----------------
        val moonUrl = episode.videoUrl

        if (!isBad(moonUrl) && moonUrl!!.contains("moonanime.art")) {

            val file = getMoonFile(moonUrl)

            if (!file.isNullOrBlank() && file.contains("m3u8")) {

                M3u8Helper.generateM3u8(
                    source = "Moon",
                    streamUrl = file,
                    referer = "https://moonanime.art/",
                    headers = mapOf("User-Agent" to userAgent)
                ).forEach(callback)

                return true
            }
        }

        // ---------------- ASHDI FALLBACK ----------------
        val ashdi = episode.fileUrl

        if (!isBad(ashdi)) {

            M3u8Helper.generateM3u8(
                source = "Ashdi",
                streamUrl = ashdi!!,
                referer = "https://ashdi.vip",
                headers = mapOf("User-Agent" to userAgent)
            ).forEach(callback)

            return true
        }

        return false
    }

    // ---------------- MOON DECODER ----------------

    private fun moonDecrypt(encoded: String, key: String = "mAnK"): String {
        return try {
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            decoded.mapIndexed { i, b ->
                (b.toInt() xor key[i % key.length].code).toChar()
            }.joinToString("")
        } catch (_: Exception) {
            ""
        }
    }

    private fun moonOuterDecode(blob: String): String {
        return try {
            val raw = Base64.decode(blob, Base64.DEFAULT)
            if (raw.size < 32) return ""

            val key = raw.copyOfRange(0, 32)
            val data = raw.copyOfRange(32, raw.size)

            data.mapIndexed { i, b ->
                (b.toInt() xor key[i % 32].toInt()).toChar()
            }.joinToString("")
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun getMoonFile(url: String): String {
        return try {

            val html = app.get(url).text

            val enc = Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
                .find(html)?.groupValues?.get(1)

            if (enc != null) {
                val dec = moonDecrypt(enc)
                if (dec.contains("m3u8")) return dec
            }

            val atob = Regex("""atob\(["']([^"']+)["']\)""")
                .find(html)?.groupValues?.get(1) ?: return ""

            val decoded = moonOuterDecode(atob)

            Regex("""file:\s*_0xd\(["']([^"']+)["']\)""")
                .find(decoded)?.groupValues?.get(1)
                ?.let { moonDecrypt(it) }
                ?: ""

        } catch (_: Exception) {
            ""
        }
    }
}
