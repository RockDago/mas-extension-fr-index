package eu.kanade.tachiyomi.lib.vkextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensionsfr.utils.Extractor
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

class VkExtractor(private val client: OkHttpClient, private val headers: Headers): Extractor() {

    override val name = "VK"

    override val supportedUrls = listOf(
        "vk.com"
    )

    override fun videosFromHoster(hoster: Hoster): List<Video> = videosFromUrl(hoster.hosterUrl)

    private val playlistRegex = Regex(""""hls":"(.*?)"""")

    private val playlistUtils = PlaylistUtils(client)

    override fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val data = client.newCall(GET(url)).execute().use { response ->
            if (response.isSuccessful) response.body.string()
            else Log.d(name, "${response.code} $url").let { return emptyList() }
        }
        val playlistUrl = playlistRegex.find(data)?.groupValues[1]?.replace("\\/", "/")
        return if (playlistUrl == null) Log.d(name, "scrape hls error $url").let { emptyList() }
        else playlistUtils.extractFromHls(playlistUrl).sortedByDescending { it.resolution }
    }
}
