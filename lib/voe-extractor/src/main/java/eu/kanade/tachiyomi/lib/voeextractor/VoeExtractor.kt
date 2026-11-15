package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.Extractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import kotlin.io.encoding.Base64
import kotlin.text.substringAfter

class VoeExtractor(private val client: OkHttpClient, private val headers: Headers = Headers.EMPTY): Extractor() {

    override val name = "VOE"

    override val supportedUrls = listOf(
        "voe.sx",
        "rebeccapracticeloss.com",
        "eugenemakedraw.com",
        "teresapoliticallearn.com",
        "megankiddifference.com",
        "jeremyparticipantanything.com",
        "jamesbornmain.com",
        "claireguessthousand.com",
        "derricknationtop.com",
        "shanelocaldog.com",
        "jamespatterncamera.com",
        "shannoncoldalready.com",
        "susanexpectcare.com",
    )

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val linkRegex = Regex("(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])")

    private fun Document.resolveRedirect(): Document {
        return if (this.selectFirst("title")!!.text().contains("Redirecting")) {
            val url = Regex("""window.location.href\s=\s["'](.*)["']""").find(this.data())!!.groups[1]!!.value
            client.newCall(GET(url, headers)).execute().asJsoup()
        } else this
    }

    override fun videosFromHoster(hoster: Hoster): List<Video> {
        val document = client.newCall(GET(hoster.hosterUrl, headers)).execute().asJsoup().resolveRedirect()
        val videos = listOf(::layout1, ::layout2, ::layout3).firstNotNullOfOrNull { it(document) }
        return videos ?: Log.d(name, "total failure").let { emptyList() }
    }

    // === Layout 1
    private fun layout1(document: Document): List<Video>? {
        val script = document.selectFirst("script:containsData(const sources), script:containsData(var sources)")?.data()
        val link = script?.substringAfter("hls': '")?.substringBefore("'")
        if (link == null) return Log.d(name, "layout1 failure").let { null }
        val playlist = if (linkRegex.matches(link)) link else Base64.decode(link).decodeToString()
        return playlistUtils.extractFromHls(playlist)
    }

    // === Layout 2
    @Serializable
    data class Layout2Dto(val file: String)

    private fun layout2(document: Document): List<Video>? {
        val script = document.selectFirst("script:containsData(wc0)")?.data()
        if (script == null) return Log.d(name, "layout2 failure").let { null }
        val base64 = Regex("'.*'").find(script)!!.value
        val decoded = Base64.decode(base64).decodeToString()
        val url = json.decodeFromString<Layout2Dto>(decoded).file
        return playlistUtils.extractFromHls(url)
    }

    // === Layout 3, obfuscated string on line 164
    private fun String.rot13() = this.map { c ->
        when (c) {
            in 'a'..'z' -> (((c.code - 'a'.code + 13) % 26) + 'a'.code).toChar()
            in 'A'..'Z' -> (((c.code - 'A'.code + 13) % 26) + 'A'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private val markers = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    @Serializable
    private data class Layout3Dto(val source: String)

    private fun layout3(document: Document): List<Video>? {
        val enc = document.selectFirst("""script[type="application/json"]""")?.data()
            ?.substringAfter('"')?.substringBefore('"')
        if (enc == null) return Log.d(name, "layout3 failure").let { null }
        val rot = enc.rot13()
        val unmarked = markers.fold(rot) { acc, marker -> acc.replace(marker, "") }
        val decoded = Base64.decode(unmarked)
        val shifted = decoded.map { b -> (b - 3).toChar() }
        val reversed = shifted.reversed().joinToString("")
        val dec = Base64.decode(reversed).decodeToString()
        val url = json.decodeFromString<Layout3Dto>(dec).source
        val playlist = playlistUtils.extractFromHls(url)
        return playlist
    }
}
