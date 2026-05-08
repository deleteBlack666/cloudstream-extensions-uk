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
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // ... (залишаємо як було)
        // скорочено для компактності
        TODO("повний код getMainPage як у твоїй версії")
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // ... (залишаємо як було)
        TODO("повний код search як у твоїй версії")
    }

    override suspend fun load(url: String): LoadResponse {
        // ... (залишаємо як було)
        TODO("повний код load як у твоїй версії")
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

        val translationsJson = fetchJsonOrNull("$mainUrl/api/player/$animeId/translations") ?: return false
        val translations = try {
            Gson().fromJson(translationsJson, TranslationsResponse::class.java).translations
        } catch (e: Exception) {
            return false
        }

        var producedAny = false

        translations@ for (item in translations) {
            val translationId = item.translation.id
            for (player in item.player) {
                var offset = 0
                while (true) {
                    val epUrl =
                        "$mainUrl/api/player/$animeId/episodes?take=100&skip=$offset&playerId=${player.id}&translationId=$translationId"
                    val epJson = fetchJsonOrNull(epUrl) ?: break

                    val parsed = try {
                        Gson().fromJson(epJson, PlayerEpisodes::class.java)
                    } catch (e: Exception) {
                        null
                    } ?: break

                    val eps = parsed.episodes ?: emptyList()
                    if (eps.isEmpty()) break

                    val episode = eps.firstOrNull { it.episode == targetEpisode }
                    if (episode != null) {
                        val fileUrl = episode.fileUrl
                        if (!fileUrl.isNullOrEmpty()) {
                            M3u8Helper.generateM3u8(
                                source = "${item.translation.name} (${player.name})",
                                streamUrl = fileUrl,
                                referer = "https://ashdi.vip"
                            ).dropLast(1).forEach(callback)
                            producedAny = true
                            break@translations
                        }

                        val videoUrl = episode.videoUrl
                        if (!videoUrl.isNullOrEmpty() && videoUrl.contains("moonanime.art")) {
                            val m3u8 = getMoonM3U(videoUrl)
                            if (m3u8.isNotEmpty()) {
                                M3u8Helper.generateM3u8(
                                    source = "${item.translation.name} (${player.name})",
                                    streamUrl = m3u8,
                                    referer = "https://moonanime.art/"
                                ).dropLast(1).forEach(callback)
                                producedAny = true
                                break@translations
                            }
                        }
                    }

                    offset += 100
                }
            }
        }

        return producedAny
    }

    // Допоміжні методи винесені в кінець класу
    private suspend fun getMoonM3U(iframeUrl: String): String {
        return try {
            val response = app.get(iframeUrl, headers = mapOf(
                "Referer" to "https://animeon.club/",
                "Origin" to "https://animeon.club",
                "User-Agent" to userAgent
            ))
            val html = response.body.string()
            val regex = Regex("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*")
            regex.find(html)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractIntFromString(string: String): Int? {
        val value = Regex("(\\d+)").findAll(string).lastOrNull() ?: return null
        return value.value.trimStart('0').ifEmpty { "0" }.toIntOrNull()
    }
}
