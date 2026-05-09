package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AnimeONProvider : MainAPI() {
    override var mainUrl = "https://anime-on.com"
    override var name = "AnimeON"
    override val hasMainPage = true
    override val hasDownload = false
    override val hasCensored = false
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // Example parsing - adjust based on actual website structure
        document.select("div.anime-item").let { items ->
            if (items.isNotEmpty()) {
                home.add(
                    HomePageList(
                        "Latest Anime",
                        items.map { item ->
                            newAnimeSearchResponse(
                                item.selectFirst("h3")?.text() ?: "Unknown",
                                item.attr("href"),
                                TvType.Anime
                            ) {
                                this.posterUrl = item.selectFirst("img")?.attr("src")
                            }
                        }
                    )
                )
            }
        }

        return HomePageResponse(home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.anime-item").map { item ->
            newAnimeSearchResponse(
                item.selectFirst("h3")?.text() ?: "Unknown",
                item.attr("href"),
                TvType.Anime
            ) {
                this.posterUrl = item.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("img.poster")?.attr("src")
        val description = document.selectFirst("div.description")?.text()

        val episodes = document.select("div.episode-list a").mapIndexed { index, element ->
            Episode(
                element.attr("href"),
                "Episode ${index + 1}",
                posterUrl = poster
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src")
            if (iframeUrl.isNotEmpty()) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = iframeUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.Iframe
                    )
                )
            }
        }
        return true
    }
}
