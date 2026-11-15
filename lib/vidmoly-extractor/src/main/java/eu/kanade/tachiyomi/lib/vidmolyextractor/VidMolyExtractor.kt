package eu.kanade.tachiyomi.lib.vidmolyextractor

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.Extractor
import okhttp3.OkHttpClient

class VidMolyExtractor(private val client: OkHttpClient): Extractor() {

    override val name = "VidMoly"

    override val supportedUrls = listOf(
        // "vidmoly.to", // Parked
        "vidmoly.me",
        "vidmoly.net",
        "vidmoly.org",
        "vidmoly.biz",
        "ansembed.net",
    )

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val sourcesRegex = Regex("""sources:\s*(.*),""")
    private val urlsRegex = Regex("""file:\s*['"](.*)['"]""")

    override fun hosterFromUrl(
        url: String,
        hosterNameGen: (String) -> String,
        lazy: Boolean,
        internalData: String
    ): Hoster {
        val fixedUrl = url.replace("vidmoly.to", "vidmoly.net")
        return super.hosterFromUrl(fixedUrl, hosterNameGen, lazy, internalData)
    }

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(sources)")!!.data()
        val sources = sourcesRegex.find(script)!!.groupValues[1]
        val urls = urlsRegex.findAll(sources).map { it.groupValues[1] }.toList()
        return urls.flatMap {
            playlistUtils.extractFromHls(it)
        }
    }
}
