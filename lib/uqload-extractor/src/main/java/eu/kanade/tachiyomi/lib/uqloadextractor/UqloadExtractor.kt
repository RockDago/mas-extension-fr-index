package eu.kanade.tachiyomi.lib.uqloadextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import org.jsoup.Jsoup
import extensionsfr.utils.Extractor
import okhttp3.Headers
import okhttp3.OkHttpClient

class UqloadExtractor(private val client: OkHttpClient): Extractor() {

    override val name = "Uqload"

    override val supportedUrls = listOf(
        "uqload.is",
        "uqload.io",
        "uqload.to",
        "uqload.vc",
        "uqload.co",
        "uqload.com",
    )

    private val playlistUtils by lazy { PlaylistUtils(client) }

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val page = client.newCall(GET(url)).execute().body.string()
        val unpacked = JsUnpacker.unpackAndCombine(page) ?: page

        val m3u8Url = Regex("""sources:\s*\[\{\s*file:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)

        if (m3u8Url != null && m3u8Url.startsWith("http")) {
            return playlistUtils.extractFromHls(m3u8Url, referer = "https://uqload.vc/")
        }

        val doc = Jsoup.parse(page)
        val script = doc.selectFirst("script:containsData(sources:)")?.data()
            ?: return emptyList()

        val videoUrl = script.substringAfter("sources: [\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val videoHeaders = Headers.headersOf("Referer", "https://uqload.co/")

        return listOf(Video(videoTitle = "Video", videoUrl = videoUrl, headers = videoHeaders))
    }
}
