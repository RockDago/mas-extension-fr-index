package eu.kanade.tachiyomi.lib.vidzyextractor

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensionsfr.utils.Extractor
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidzyExtractor(private val client: OkHttpClient) : Extractor() {

    override val name = "Vidzy"

    override val supportedUrls = listOf(
        "vidzy.org",
        "vidzy.cc",
        "vidzy.live",
        "vidzy.lol",
        "vidzy.pro",
    )

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val m3u8SrcRegex = Regex("""sources:\s*\[\{\s*src:\s*["']([^"']+\.m3u8[^"']*)["']""")
    private val m3u8FileRegex = Regex("""(?:sources|file):\s*(?:\[\{\s*)?(?:file|src):\s*["']([^"']+\.m3u8[^"']*)["']""")
    private val fallbackRegex = Regex("""["'](https?://[^"']+\.urlset/master\.m3u8[^"']*)["']""")

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        return videosFromUrl(hoster.hosterUrl, hoster.hosterName)
    }

    fun videosFromUrl(url: String, prefix: String = "Vidzy"): List<Video> {
        return try {
            val headers = Headers.Builder()
                .set("Referer", url)
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()

            val masterUrl = m3u8SrcRegex.find(body)?.groupValues?.get(1)
                ?: m3u8FileRegex.find(body)?.groupValues?.get(1)
                ?: fallbackRegex.find(body)?.groupValues?.get(1)
                ?: return emptyList()

            playlistUtils.extractFromHls(
                masterUrl,
                referer = "https://vidzy.org/",
                videoNameGen = { "$prefix - $it" },
            )
        } catch (_: Exception) {
            emptyList()
        }
    }
}
