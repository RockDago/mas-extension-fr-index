package eu.kanade.tachiyomi.lib.smoothpreextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensionsfr.utils.Extractor
import okhttp3.OkHttpClient

class SmoothpreExtractor(private val client: OkHttpClient): Extractor() {
    override val name = "Smoothpre"

    override val supportedUrls = listOf(
        "smoothpre.com",
        "morencius.com",
        "movearnpre.com",
        "dingtezuni.com",
        "earnvids.com",
    )

    private val playlistUtils = PlaylistUtils(client)

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        val page = client.newCall(GET(hoster.hosterUrl)).execute().body.string()
        val playlistUrl = JsUnpacker.unpackAndCombine(page)
            ?.substringAfter("hls2\":\"")
            ?.substringBefore('"')
        return if (playlistUrl != null) playlistUtils.extractFromHls(playlistUrl)
        else emptyList()
    }

}