package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*

class AnimeONProvider : MainAPI() {

    override var mainUrl = "https://animeon.club"
    override var name = "AnimeON"
    override val hasMainPage = true
    override var lang = "uk"

    private val apiUrl = "$mainUrl/api/anime"
    private val posterApi = "$mainUrl/api/uploads/images/%s"

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome Mobile"

    // ---------------- SAFE REQUEST ----------------
    private suspend fun getJson(url: String): String? {
        return try {
            app.get(
                url,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).text
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {
        val animeId = url.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("Bad id")

        val json = getJson("$apiUrl/$animeId")
            ?: throw ErrorLoadingException("No data")

        val anime = Gson().fromJson(json, AnimeInfoModel::class.java)

        val episodes = arrayListOf<Episode>()

        // ---------------- EPISODES ----------------
        val transJson = getJson("$mainUrl/api/player/$animeId/translations")

        if (transJson != null) {
            try {
                val trans = Gson().fromJson(transJson, TranslationsResponse::class.java)

                trans.translations.forEach { t ->
                    t.player.forEach { player ->

                        for (skip in 0..2000 step 100) {
                            val epJson = getJson(
                                "$mainUrl/api/player/$animeId/episodes?take=100&skip=$skip&playerId=${player.id}&translationId=${t.translation.id}"
                            ) ?: continue

                            val eps = try {
                                Gson().fromJson(epJson, PlayerEpisodes::class.java).episodes
                            } catch (e: Exception) {
                                null
                            } ?: continue

                            eps.forEach { ep ->
                                episodes.add(
                                    Episode(
                                        Data = "$animeId,${ep.episode},${ep.id}",
                                        Name = "EP ${ep.episode}",
                                        Episode = ep.episode
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return newAnimeLoadResponse(anime.titleUa, url, TvType.Anime) {
            posterUrl = posterApi.format(anime.image.preview)
            this.engName = anime.titleEn
            this.plot = anime.description
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(anime.malId.toIntOrNull())
        }
    }

    // ---------------- LINKS (MOON FIRST) ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split(",")
        if (parts.size < 3) return false

        val animeId = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false
        val epId = parts[2]

        val transJson = getJson("$mainUrl/api/player/$animeId/translations") ?: return false

        val trans = try {
            Gson().fromJson(transJson, TranslationsResponse::class.java)
        } catch (e: Exception) {
            return false
        }

        for (t in trans.translations) {
            for (player in t.player) {

                val epJson = getJson(
                    "$mainUrl/api/player/$epId/episode"
                )

                val ep = try {
                    Gson().fromJson(epJson, FundubEpisode::class.java)
                } catch (e: Exception) {
                    null
                }

                // ---------------- 1. MOON FIRST ----------------
                ep?.videoUrl?.let { url ->
                    if (url.contains("moonanime.art")) {

                        M3u8Helper.generateM3u8(
                            source = "Moon",
                            streamUrl = url,
                            referer = "https://moonanime.art/"
                        ).forEach(callback)

                        return true
                    }
                }

                // ---------------- 2. ASHDI FALLBACK ----------------
                ep?.fileUrl?.let { url ->
                    M3u8Helper.generateM3u8(
                        source = "Ashdi",
                        streamUrl = url,
                        referer = "https://ashdi.vip"
                    ).forEach(callback)

                    return true
                }
            }
        }

        return false
    }
}
