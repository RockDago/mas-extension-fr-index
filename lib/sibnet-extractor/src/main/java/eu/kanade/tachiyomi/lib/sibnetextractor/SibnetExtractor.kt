package eu.kanade.tachiyomi.lib.sibnetextractor

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.get
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.Extractor
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.internal.http2.Header

class SibnetExtractor(private val client: OkHttpClient): Extractor() {

    override val name = "Sibnet"

    override val supportedUrls = listOf(
        "sibnet.ru"
    )

    override fun videosFromHoster(hoster: Hoster) = listOf(
        Video(videoTitle = "Video", videoUrl = hoster.hosterUrl, initialized = false),
    )

    override fun resolveVideo(video: Video): Video {
        val url = video.videoUrl
        val document = client.newCall(GET(url),).execute().asJsoup()
        val script = document.selectFirst("script:containsData(player.src)")?.data()!!
        val slug = script.substringAfter("player.src").substringAfter("src:")
            .substringAfter("\"").substringBefore("\"")

        val videoUrl = if (slug.contains("http")) {
            slug
        } else {
            "https://${url.toHttpUrl().host}$slug"
        }

        val headers = mapOf(
            "Referer" to url
        ).toHeaders()

        return video.copy(
            videoUrl = videoUrl,
            headers = headers,
        )
    }

    override fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val document = client.newCall(
            GET(url),
        ).execute().asJsoup()
        val script = document.selectFirst("script:containsData(player.src)")?.data() ?: return emptyList()
        val slug = script.substringAfter("player.src").substringAfter("src:")
            .substringAfter("\"").substringBefore("\"")

        val videoUrl = if (slug.contains("http")) {
            slug
        } else {
            "https://${url.toHttpUrl().host}$slug"
        }

        val headers = mapOf(
            "Referer" to url
        ).toHeaders()

        return listOf(Video(
            videoTitle = "Video",
            videoUrl = videoUrl,
            headers = headers,
            initialized = true,
        ))
    }
}
