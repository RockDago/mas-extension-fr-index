package eu.kanade.tachiyomi.lib.upstreamextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.Extractor
import okhttp3.OkHttpClient

class UpstreamExtractor(private val client: OkHttpClient): Extractor() {
    override val name = "Upstream"

    override val supportedUrls: List<String> = listOf(
        "upstream"
    )

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return runCatching {
            val jsE = client.newCall(GET(url)).execute().asJsoup().selectFirst("script:containsData(eval)")!!.data()
            val masterUrl = JsUnpacker.unpackAndCombine(jsE)!!.substringAfter("{file:\"").substringBefore("\"}")
            PlaylistUtils(client).extractFromHls(masterUrl)
        }.getOrDefault(emptyList())
    }
}
