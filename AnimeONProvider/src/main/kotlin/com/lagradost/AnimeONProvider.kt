package com.lagradost

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.models.*
import java.net.URLEncoder

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

    private suspend fun fetchJsonOrNull(url: String): String? {
        return try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            if (response.contains("cloudflare", ignoreCase = true) ||
                response.contains("cf-browser-verification", ignoreCase = true) ||
                (!response.trimStart().startsWith("{") && !response.trimStart().startsWith("["))
            ) null else response
        } catch (e: Exception) { null }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val patterns = listOf(
            Regex("(?:серія|епізод|episode|series|seria)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("#(\\d+)"),
            Regex("(\\d+)\\s*(?:серія|епізод|episode|series|seria)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)")
        )
        for (pat in patterns) {
            pat.find(text)?.let { match ->
                return match.groupValues[1].toIntOrNull()
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) return newHomePageResponse(emptyList())
        val jsonText = fetchJsonOrNull(request.data.format(page)) ?: return newHomePageResponse(request.name, emptyList())
        val homeList = try {
            val model = Gson().fromJson(jsonText, NewAnimeModel::class.java)
            model.results?.mapNotNull { result ->
                result?.let {
                    newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                        posterUrl = posterApi.format(it.image.preview)
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            try {
                val arrayType = object : TypeToken<Array<AnimeModel>>() {}.type
                val items: Array<AnimeModel> = Gson().fromJson(jsonText, arrayType)
                items.map { item ->
                    newAnimeSearchResponse(item.titleUa, "anime/${item.id}", TvType.Anime) {
                        posterUrl = posterApi.format(item.image.preview)
                    }
                }
            } catch (e2: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val jsonText = fetchJsonOrNull(searchApi + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        val searchModel = try { Gson().fromJson(jsonText, SearchModel::class.java) } catch (e: Exception) { return emptyList() }
        return searchModel.result?.mapNotNull { result ->
            result?.let {
                newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                    posterUrl = posterApi.format(it.image.preview)
                    addDubStatus(isDub = true, it.episodes)
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val apiUrlPath = url.replace("/anime/", "/api/anime/")
        val jsonText = fetchJsonOrNull(apiUrlPath) ?: throw Exception("Не вдалося завантажити інформацію про аніме")
        val animeInfo = try { Gson().fromJson(jsonText, AnimeInfoModel::class.java) } catch (e: Exception) { throw Exception("Помилка парсингу JSON") }
        val animeId = animeInfo.id

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        try {
            val doc = app.get(url).document
            val selectors = listOf(
                "a[href*='/watch/']",
                "a[href*='/episode/']",
                "a[href*='/ep/']",
                ".episodes a",
                ".episode-item a",
                ".serie-item a",
                "a.series-episode"
            )
            var episodeElements = mutableListOf<org.jsoup.nodes.Element>()
            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    episodeElements = elements.toList().toMutableList()
                    break
                }
            }
            if (episodeElements.isEmpty()) {
                val allLinks = doc.select("a")
                episodeElements = allLinks.filter { a ->
                    val href = a.attr("href")
                    (href.startsWith("/anime/") && href != "/anime/$animeId") || a.text().matches(Regex(".*\\d+.*"))
                }.toMutableList()
            }

            for (a in episodeElements) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                var epNum = extractEpisodeNumber(a.text())
                if (epNum == null) epNum = extractEpisodeNumber(href)
                if (epNum == null) continue
                val fullUrl = if (href.startsWith("/")) mainUrl + href else href
                episodes.add(
                    newEpisode(fullUrl) {
                        name = "Епізод $epNum"
                        this.episode = epNum
                        posterUrl = animeInfo.image.preview
                    }
                )
            }
            episodes.sortBy { it.episode }
        } catch (e: Exception) { }

        val showStatus = if (animeInfo.status?.contains("ongoing") == true) ShowStatus.Ongoing else ShowStatus.Completed
        val tvType = when {
            animeInfo.type?.contains("tv") == true -> TvType.Anime
            animeInfo.type?.contains("OVA") == true -> TvType.OVA
            animeInfo.type?.contains("ONA") == true -> TvType.OVA
            animeInfo.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(animeInfo.titleUa, "$mainUrl/anime/$animeId", tvType) {
            posterUrl = posterApi.format(animeInfo.image.preview)
            engName = animeInfo.titleEn
            tags = animeInfo.genres?.map { it.nameUa } ?: emptyList()
            plot = animeInfo.description
            addTrailer(animeInfo.trailer)
            this.showStatus = showStatus
            year = animeInfo.releaseDate.toIntOrNull()
            score = animeInfo.rating?.toDoubleOrNull()?.let { Score.from10(it) }
            addEpisodes(DubStatus.Dubbed, episodes)
            addMalId(animeInfo.malId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("http")) return false
        val doc = app.get(data).document
        val scripts = doc.select("script").eachText().joinToString("\n")
        val match = fileRegex.find(scripts)
        if (match != null) {
            val videoUrl = match.groupValues[1]
            if (videoUrl.isNotEmpty()) {
                val finalUrl = if (videoUrl.contains(".m3u8")) videoUrl else getM3U8FromPage(videoUrl)
                if (finalUrl.isNotEmpty()) {
                    M3u8Helper.generateM3u8("AnimeON", finalUrl, referer = mainUrl).dropLast(1).forEach(callback)
                    return true
                }
            }
        }
        val iframe = doc.select("iframe[src*='player'], iframe[src*='video']").firstOrNull()
        if (iframe != null) {
            val iframeUrl = iframe.attr("src")
            if (iframeUrl.isNotBlank()) {
                val fullIframe = if (iframeUrl.startsWith("/")) mainUrl + iframeUrl else iframeUrl
                val iframeDoc = app.get(fullIframe).document
                val iframeScripts = iframeDoc.select("script").html()
                val iframeMatch = fileRegex.find(iframeScripts)
                if (iframeMatch != null) {
                    val videoUrl = iframeMatch.groupValues[1]
                    val finalUrl = if (videoUrl.contains(".m3u8")) videoUrl else getM3U8FromPage(videoUrl)
                    if (finalUrl.isNotEmpty()) {
                        M3u8Helper.generateM3u8("AnimeON", finalUrl, referer = mainUrl).dropLast(1).forEach(callback)
                        return true
                    }
                }
            }
        }
        return false
    }

    private suspend fun getM3U8FromPage(url: String): String {
        val response = app.get(url, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
        val html = response.document.select("script").html()
        return fileRegex.find(html)?.groupValues?.get(1) ?: ""
    }
}
