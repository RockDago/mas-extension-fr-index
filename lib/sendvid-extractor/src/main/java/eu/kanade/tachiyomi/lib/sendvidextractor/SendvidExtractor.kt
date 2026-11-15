package eu.kanade.tachiyomi.lib.sendvidextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.Extractor
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class SendvidExtractor(private val client: OkHttpClient, private val headers: Headers): Extractor() {

    override val name = "Sendvid"

    override val supportedUrls = listOf(
        "sendvid.com",
    )

    override fun videosFromHoster(hoster: Hoster) = videosFromUrl(hoster.hosterUrl)

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val document = client.newCall(GET(url, headers)).execute().use { response ->
            if (response.isSuccessful) response.asJsoup()
            else Log.d(name, "404 $url").let { return@videosFromUrl emptyList() }
        }
        val masterUrl = document.selectFirst("source#video_source")?.attr("src") ?: return emptyList()

        return if (masterUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(masterUrl, url, videoNameGen = { "${it}p" })
        } else {
            val httpUrl = "https://${url.toHttpUrl()}"
            val videoHeaders = headers.newBuilder()
                .set("Origin", httpUrl)
                .set("Referer", "$httpUrl/")
                .build()
            listOf(Video(videoTitle = "Video", videoUrl = masterUrl, headers = videoHeaders, initialized = true))
        }
    }
}
