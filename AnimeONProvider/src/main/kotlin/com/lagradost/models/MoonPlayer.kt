package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class MoonPlayer : ExtractorApi() {
    override val name = "Moon"
    override val mainUrl = "https://moonanime.art"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = mainUrl).text
        
        val m3u8Regex = """https?://s\.moonanime\.art/content/stream/[^"']+\.m3u8[^"']*""".toRegex()
        val extractedUrl = m3u8Regex.find(response)?.value ?: return

        val headers = mapOf(
            "Origin" to "https://moonanime.art",
            "Referer" to "https://moonanime.art/",
            "Accept" to "*/*"
        )

        // ВИПРАВЛЕНО: параметр тепер називається streamUrl
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = extractedUrl, 
            referer = "$mainUrl/",
            headers = headers,
            name = name
        ).forEach { link ->
            callback.invoke(link)
        }
    }
}
