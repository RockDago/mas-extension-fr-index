package eu.kanade.tachiyomi.animeextension.fr.dessinanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidzyextractor.VidzyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.AnimeDomainSource
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class DessinAnime : AnimeDomainSource() {

    override val name = "DessinAnime"

    override val lang = "fr"

    override val supportsLatest = false

    override var baseUrl by LazyMutable {
        preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Map to keep track of cursor per page for pagination
    private val popularCursorMap = ConcurrentHashMap<Int, Long>()
    private val searchCursorMap = ConcurrentHashMap<Int, Long>()

    // ============================ Extractors ============================

    private class FilemoonWrapper(private val client: OkHttpClient) : Extractor() {
        override val name = "Filemoon"
        override val supportedUrls = listOf("filemoon.sx", "filemoon.to", "filemoon.nl", "mxzhxco8dft.com")
        private val extractor by lazy { FilemoonExtractor(client) }
        override fun videosFromHoster(hoster: Hoster): List<Video> {
            return extractor.videosFromUrl(hoster.hosterUrl, "${hoster.hosterName} - ")
        }
    }

    private class StreamWishWrapper(private val client: OkHttpClient, private val headers: Headers) : Extractor() {
        override val name = "StreamWish"
        override val supportedUrls = listOf("streamwish", "wishembed", "mwish", "strwish", "awish", "sugarswish")
        private val extractor by lazy { StreamWishExtractor(client, headers) }
        override fun videosFromHoster(hoster: Hoster): List<Video> {
            return extractor.videosFromUrl(hoster.hosterUrl, hoster.hosterName)
        }
    }

    override val extractorList: List<Extractor> = listOf(
        VidzyExtractor(client),
        VoeExtractor(client, headers),
        UqloadExtractor(client),
        StreamWishWrapper(client, headers),
        VidMolyExtractor(client),
        SibnetExtractor(client),
        SendvidExtractor(client, headers),
        FilemoonWrapper(client),
    )

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        if (page == 1) {
            popularCursorMap.clear()
            return GET("$baseUrl/api/catalogue", headers)
        }
        val cursor = popularCursorMap[page]
        val url = if (cursor != null) "$baseUrl/api/catalogue?cursor=$cursor" else "$baseUrl/api/catalogue"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonStr = response.body.string()
        val jsonArr = try { JSONArray(jsonStr) } catch (_: Exception) { JSONArray() }
        val animeList = mutableListOf<SAnime>()

        var lastId: Long? = null
        for (i in 0 until jsonArr.length()) {
            val item = jsonArr.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1)
            val slug = item.optString("slug")
            val title = item.optString("title")
            val poster = item.optString("posterPath")
            val mediaType = item.optString("mediaType", "TV")

            if (slug.isNotBlank() && title.isNotBlank()) {
                val anime = SAnime.create().apply {
                    this.title = title
                    thumbnail_url = poster
                    val path = if (mediaType.equals("MOVIE", ignoreCase = true)) "/movie/$slug" else "/tv/$slug"
                    setUrlWithoutDomain(path)
                    fetch_type = FetchType.Episodes
                }
                animeList.add(anime)
                if (id > 0) lastId = id
            }
        }

        val hasNextPage = jsonArr.length() >= 36 && lastId != null
        if (hasNextPage && lastId != null) {
            val requestUrl = response.request.url
            val currentCursor = requestUrl.queryParameter("cursor")?.toLongOrNull()
            val nextPageNum = if (currentCursor == null) 2 else {
                popularCursorMap.entries.firstOrNull { it.value == currentCursor }?.key?.plus(1) ?: 2
            }
            popularCursorMap[nextPageNum] = lastId
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            return GET("$baseUrl/api/search?q=$encodedQuery", headers)
        }

        if (page == 1) {
            searchCursorMap.clear()
        }

        var mediaTypeParam = ""
        var channelParam = ""

        for (filter in filters) {
            when (filter) {
                is MediaTypeFilter -> if (filter.toUriPart().isNotBlank()) mediaTypeParam = filter.toUriPart()
                is ChannelFilter -> if (filter.toUriPart().isNotBlank()) channelParam = filter.toUriPart()
                else -> {}
            }
        }

        val params = mutableListOf<String>()
        if (mediaTypeParam.isNotBlank()) params.add(mediaTypeParam)
        if (channelParam.isNotBlank()) params.add(channelParam)

        if (page > 1) {
            val cursor = searchCursorMap[page]
            if (cursor != null) params.add("cursor=$cursor")
        }

        val queryString = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return GET("$baseUrl/api/catalogue$queryString", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val jsonStr = response.body.string()
        val jsonArr = try { JSONArray(jsonStr) } catch (_: Exception) { JSONArray() }
        val animeList = mutableListOf<SAnime>()

        var lastId: Long? = null
        for (i in 0 until jsonArr.length()) {
            val item = jsonArr.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1)
            val slug = item.optString("slug")
            val title = item.optString("title")
            val poster = item.optString("posterPath")
            val mediaType = item.optString("mediaType", "TV")

            if (slug.isNotBlank() && title.isNotBlank()) {
                val anime = SAnime.create().apply {
                    this.title = title
                    thumbnail_url = poster
                    val path = if (mediaType.equals("MOVIE", ignoreCase = true)) "/movie/$slug" else "/tv/$slug"
                    setUrlWithoutDomain(path)
                    fetch_type = FetchType.Episodes
                }
                animeList.add(anime)
                if (id > 0) lastId = id
            }
        }

        val isSearchQuery = response.request.url.encodedPath.contains("/api/search")
        val hasNextPage = !isSearchQuery && jsonArr.length() >= 36 && lastId != null

        if (hasNextPage && lastId != null) {
            val requestUrl = response.request.url
            val currentCursor = requestUrl.queryParameter("cursor")?.toLongOrNull()
            val nextPageNum = if (currentCursor == null) 2 else {
                searchCursorMap.entries.firstOrNull { it.value == currentCursor }?.key?.plus(1) ?: 2
            }
            searchCursorMap[nextPageNum] = lastId
        }

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        checkBaseUrl()
        return super.getAnimeDetails(anime).apply {
            fetch_type = FetchType.Episodes
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("p.text-muted-foreground, div.description, p[data-slot=description]")?.text() ?: ""
            thumbnail_url = document.selectFirst("img[alt]")?.let {
                it.absUrl("src").ifBlank { it.attr("src") }
            } ?: ""
            genre = document.select("span.badge, a[href*='channel='], a[href*='genre=']").joinToString(", ") {
                it.text()
            }
            status = SAnime.COMPLETED
            fetch_type = FetchType.Episodes
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val path = response.request.url.encodedPath
        val episodeList = mutableListOf<SEpisode>()

        if (path.contains("/movie/")) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Film complet (VF)"
                    episode_number = 1F
                    url = path
                }
            )
            return episodeList
        }

        // TV Series: parse episode links matching /tv/{slug}/{season}/{episode}
        val epRegex = Regex("""/tv/[^/]+/(\d+)/(\d+)""")
        val seenUrls = mutableSetOf<String>()

        document.select("a[href*='/tv/']").forEach { link ->
            val href = link.attr("href")
            val match = epRegex.find(href) ?: return@forEach
            val cleanHref = href.substringBefore("?")
            if (!seenUrls.add(cleanHref)) return@forEach

            val seasonNum = match.groupValues[1].toIntOrNull() ?: 1
            val epNum = match.groupValues[2].toIntOrNull() ?: 1

            val titleText = link.selectFirst(".font-extrabold")?.text()
            val badgeText = link.selectFirst("span.rotate-3, span:contains(S)")?.text()
                ?: "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')}"

            val epName = if (!titleText.isNullOrBlank() && !titleText.startsWith("Épisode", ignoreCase = true)) {
                "$badgeText - $titleText (VF)"
            } else {
                "$badgeText (VF)"
            }

            episodeList.add(
                SEpisode.create().apply {
                    name = epName
                    episode_number = seasonNum * 1000F + epNum
                    url = cleanHref
                }
            )
        }

        // Also extract from __NEXT_DATA__ JSON if HTML episode links were not rendered
        if (episodeList.isEmpty()) {
            val nextDataStr = document.selectFirst("script#__NEXT_DATA__")?.data()
            if (!nextDataStr.isNullOrBlank()) {
                try {
                    val nextJson = org.json.JSONObject(nextDataStr)
                    val pageProps = nextJson.optJSONObject("props")?.optJSONObject("pageProps")
                    val series = pageProps?.optJSONObject("series") ?: pageProps?.optJSONObject("item")
                    val seasons = series?.optJSONArray("seasons")
                    if (seasons != null) {
                        for (s in 0 until seasons.length()) {
                            val seasonObj = seasons.optJSONObject(s) ?: continue
                            val sNum = seasonObj.optInt("seasonNumber", s + 1)
                            val eps = seasonObj.optJSONArray("episodes") ?: continue
                            for (e in 0 until eps.length()) {
                                val epObj = eps.optJSONObject(e) ?: continue
                                val epNum = epObj.optInt("episodeNumber", e + 1)
                                val epTitle = epObj.optString("title")
                                val slug = series.optString("slug", path.trim('/').substringAfterLast('/'))
                                val epName = if (epTitle.isNotBlank()) "S${sNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} - $epTitle (VF)"
                                else "S${sNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} (VF)"
                                episodeList.add(
                                    SEpisode.create().apply {
                                        name = epName
                                        episode_number = sNum * 1000F + epNum
                                        url = "/tv/$slug/$sNum/$epNum"
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback: If no episode links were found, add single episode
        if (episodeList.isEmpty()) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Épisode complet (VF)"
                    episode_number = 1F
                    url = path
                }
            )
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val targetUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val doc = try {
            client.newCall(GET(targetUrl, headers)).execute().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val playerUrls = mutableListOf<String>()

        // 1. Extract from iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.startsWith("http")) playerUrls.add(src)
        }

        // 2. Extract from player elements data attributes
        doc.select("[data-url], [data-src], [data-player], [data-embed]").forEach { el ->
            val u = el.attr("data-url").ifBlank {
                el.attr("data-src").ifBlank {
                    el.attr("data-player").ifBlank {
                        el.attr("data-embed")
                    }
                }
            }.trim()
            if (u.startsWith("http")) playerUrls.add(u)
        }

        // 3. Extract player URLs from inline scripts
        val scriptContent = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val streamUrlRegex = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/(?:e|embed|v)/[a-zA-Z0-9_-]+""")
        streamUrlRegex.findAll(scriptContent).forEach { match ->
            playerUrls.add(match.value)
        }

        return playerUrls.distinct().mapNotNull { rawUrl ->
            val name = guessHosterName(rawUrl)
            Hoster(
                hosterUrl = rawUrl,
                hosterName = name,
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val resolvedUrl = resolveRedirectIfNeeded(hoster.hosterUrl)
            val resolvedHoster = hoster.copy(hosterUrl = resolvedUrl)
            val extractor = extractorList.firstOrNull {
                it.name.equals(hoster.hosterName, ignoreCase = true) || it.supportsUrl(resolvedUrl) || it.supportsUrl(hoster.hosterUrl)
            }
            extractor?.videosFromHoster(resolvedHoster)?.map {
                it.copy(preferred = isPreferred(hoster, it))
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return getHosterList(episode).flatMap { hoster ->
            try {
                getVideoList(hoster)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    private fun guessHosterName(url: String): String {
        val lower = url.lowercase()
        return when {
            "vidzy" in lower -> "Vidzy"
            "uqload" in lower -> "Uqload"
            "voe" in lower || "osaka_go" in lower || "susanexpectcare" in lower -> "VOE"
            "filemoon" in lower || "grandline_go" in lower || "mxzhxco" in lower -> "Filemoon"
            "dood" in lower || "tokyo_go" in lower || "playmogo" in lower -> "Doodstream"
            "streamwish" in lower || "wishembed" in lower || "mwish" in lower || "strwish" in lower -> "StreamWish"
            "vidmoly" in lower -> "VidMoly"
            "sibnet" in lower -> "Sibnet"
            "sendvid" in lower -> "Sendvid"
            else -> extractorList.firstOrNull { it.supportsUrl(url) }?.name ?: "Lecteur"
        }
    }

    private fun resolveRedirectIfNeeded(url: String): String {
        if (!url.contains("kokoflix.lol") && !url.contains("trakx.lol")) {
            return url
        }
        return try {
            val response = client.newCall(GET(url, headers)).execute()
            response.request.url.toString()
        } catch (_: Exception) {
            url
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La recherche par mot-clé ignore les filtres ci-dessous"),
        MediaTypeFilter(),
        ChannelFilter(),
    )

    private class MediaTypeFilter : UriPartFilter(
        "Type de média",
        arrayOf(
            Pair("Tous", ""),
            Pair("Séries", "mediaType=TV"),
            Pair("Films", "mediaType=MOVIE"),
        ),
    )

    private class ChannelFilter : UriPartFilter(
        "Chaîne / Diffuseur",
        arrayOf(
            Pair("Toutes les chaînes", ""),
            Pair("Cartoon Network", "channel=cartoon-network"),
            Pair("Disney+", "channel=disney-plus"),
            Pair("Gulli", "channel=gulli"),
            Pair("Netflix", "channel=netflix"),
            Pair("Canal+", "channel=canal-plus"),
            Pair("France TV", "channel=france-tv"),
            Pair("Paramount+", "channel=paramount-plus"),
            Pair("Prime Video", "channel=prime-video"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
    }

    companion object {
        private const val DEFAULT_DOMAIN = "https://dessinanime.cc"
    }
}
