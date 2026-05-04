package com.lagradost

import com.google.gson.Gson
import com.lagradost.cloudstream3.*
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
    private val searchApi = "$apiUrl/search?=text="

    val fileRegex = "file\\s*:\\s*[\"']([^\",']+?)[\"']".toRegex()

    override val mainPage = mainPageOf(
        "$apiUrl/popular" to "Популярне",
        "$apiUrl/seasons" to "Аніме поточного сезону",
        "$apiUrl?pageSize=24&pageIndex=%d" to "Нове",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!request.data.contains("pageIndex") && page != 1) {
            return newHomePageResponse(emptyList())
        }

        val document = app.get(
            request.data.format(page),
            headers = mapOf("Referer" to mainUrl)
        ).text

        val parsedJSON = Gson().fromJson(document, NewAnimeModel::class.java)

        val homeList = parsedJSON.results.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image.preview)
            }
        }

        return newHomePageResponse(request.name, homeList)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val animeJSON = Gson().fromJson(
            app.get(
                searchApi + query,
                headers = mapOf("Referer" to mainUrl)
            ).text,
            SearchModel::class.java
        )

        return animeJSON.result.map {
            newAnimeSearchResponse(it.titleUa, "anime/${it.id}", TvType.Anime) {
                this.posterUrl = posterApi.format(it.image.preview)
                addDubStatus(isDub = true, it.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeJSON = Gson().fromJson(
            app.get(
                url.replace("/anime/", "/api/anime/"),
                headers = mapOf("Referer" to "$mainUrl/")
            ).text,
            AnimeInfoModel::class.java
        )

        val showStatus = when {
            animeJSON.status?.contains("ongoing") == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }

        val tvType = when {
            animeJSON.type?.contains("tv") == true -> TvType.Anime
            animeJSON.type?.contains("OVA") == true -> TvType.OVA
            animeJSON.type?.contains("ONA") == true -> TvType.OVA
            animeJSON.type?.contains("movie") == true -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        val episodes = mutableListOf<Episode>()

        val fundubs = Gson().fromJson(
            app.get("$mainUrl/api/player/fundubs/${animeJSON.id}").text,
            FundubsModel::class.java
        ).fundubs

        if (fundubs.isNotEmpty()) {
            val epJson = Gson().fromJson(
                app.get(
                    "$mainUrl/api/player/episodes/${animeJSON.id}?playerId=${fundubs[0].player[0].id}&fundubId=${fundubs[0].fundub.id}"
                ).text,
                PlayerEpisodes::class.java
            )

            epJson.episodes.forEach { ep ->
                episodes.add(
                    newEpisode("${animeJSON.id}, ${ep.episode}") {
                        this.name = "Епізод ${ep.episode}"
                        this.posterUrl = ep.poster
                        this.episode = ep.episode
                        this.data = "${animeJSON.id}, ${ep.episode}"
                    }
                )
            }
        }

        return newAnimeLoadResponse(
            animeJSON.titleUa,
            "$mainUrl/anime/${animeJSON.id}",
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

        val fundubs = Gson().fromJson(
            app.get("$mainUrl/api/player/fundubs/${dataList[0]}").text,
            FundubsModel::class.java
        ).fundubs

        fundubs.forEach { dub ->
            val videoUrl = app.get(
                "${apiUrl}/player/${dataList[0]}/${dub.player[0].id}/${dub.fundub.id}"
            ).parsedSafe<FundubVideoUrl>()?.videoUrl ?: return@forEach

            M3u8Helper.generateM3u8(
                source = "${dub.fundub.name} (${dub.player[0].name})",
                streamUrl = getM3U(videoUrl),
                referer = ""
            ).dropLast(1).forEach(callback)
        }

        return true
    }

    private fun extractIntFromString(string: String): Int? {
        return Regex("(\\d+)").find(string)?.value?.toIntOrNull()
    }

    private suspend fun getM3U(url: String): String {
        return fileRegex.find(app.get(url).document.select("script").html())
            ?.groups?.get(1)?.value ?: ""
    }
}
