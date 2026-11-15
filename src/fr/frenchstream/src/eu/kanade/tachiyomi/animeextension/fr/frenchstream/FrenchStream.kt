package eu.kanade.tachiyomi.animeextension.fr.frenchstream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidzyextractor.VidzyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.AnimeDomainSource
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FrenchStream : AnimeDomainSource() {

    override val name = "French Stream"

    override val lang = "fr"

    override val supportsLatest = true

    override var baseUrl by LazyMutable {
        preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================ Extractors ============================

    private class FilemoonWrapper(private val client: OkHttpClient) : Extractor() {
        override val name = "Filemoon"
        override val supportedUrls = listOf("filemoon.sx", "filemoon.to", "filemoon.nl", "mxzhxco8dft.com")
        private val extractor by lazy { FilemoonExtractor(client) }
        override fun videosFromHoster(hoster: Hoster): List<Video> {
            return extractor.videosFromUrl(hoster.hosterUrl, "${hoster.hosterName} - ")
        }
    }

    private class DoodWrapper(private val client: OkHttpClient) : Extractor() {
        override val name = "Doodstream"
        override val supportedUrls = listOf("dood.to", "dood.so", "dood.la", "dood.ws", "doodstream.com", "doods.pro", "ds2play.com", "d0o0d.com", "do0od.com", "playmogo.com")
        private val extractor by lazy { DoodExtractor(client) }
        override fun videosFromHoster(hoster: Hoster): List<Video> {
            return extractor.videoFromUrl(hoster.hosterUrl, hoster.hosterName)?.let { listOf(it) } ?: emptyList()
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
        FilemoonWrapper(client),
        DoodWrapper(client),
    )

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/films/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        parseAnimeList(response.asJsoup())

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        parseAnimeList(response.asJsoup())

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("search_start", page.toString())
                .add("full_search", "0")
                .add("result_from", ((page - 1) * 10 + 1).toString())
                .add("story", query.trim())
                .build()

            val postHeaders = headers.newBuilder()
                .add("Content-Type", "application/x-www-form-urlencoded")
                .build()

            return POST("$baseUrl/index.php?do=search", postHeaders, body)
        }

        var path = ""
        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
                    if (filter.toUriPart().isNotBlank()) {
                        path = filter.toUriPart()
                    }
                }
                is GenreFilter -> {
                    if (filter.toUriPart().isNotBlank()) {
                        path = filter.toUriPart()
                    }
                }
                else -> {}
            }
        }

        val targetUrl = if (path.isNotBlank()) {
            "$baseUrl$path/page/$page/"
        } else {
            "$baseUrl/page/$page/"
        }

        return GET(targetUrl, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        parseAnimeList(response.asJsoup())

    private fun parseAnimeList(document: Document): AnimesPage {
        val elements = document.select("div#dle-content div.short")
        val animeList = elements.mapNotNull { element ->
            val linkEl = element.selectFirst("a.short-poster") ?: element.selectFirst("a[href]")
            val href = linkEl?.attr("href") ?: return@mapNotNull null
            val titleText = element.selectFirst("div.short-title")?.text()
                ?: linkEl.attr("alt").takeIf { it.isNotBlank() }
                ?: linkEl.text().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val imgEl = element.selectFirst("img")
            val imgUrl = imgEl?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } } ?: ""

            SAnime.create().apply {
                title = titleText.trim()
                thumbnail_url = imgUrl
                val cleanUrl = href.toHttpUrlOrNull()?.encodedPath ?: href
                setUrlWithoutDomain(cleanUrl)
                fetch_type = FetchType.Episodes
            }
        }

        val hasNextPage = document.selectFirst("div.pagi-nav .pnext a, span.pnext a, span.navigation span + a") != null
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
            title = document.selectFirst("h1.f-title, div.f-title")?.text()
                ?: document.selectFirst("h1")?.text() ?: ""
            description = document.selectFirst("div.f-desc, div.mov-desc, div[itemprop=description]")?.text() ?: ""
            genre = document.select("ul.f-meta a[href*='/genre/'], div.mov-desc a[href*='/genre/']").joinToString(", ") {
                it.text()
            }
            thumbnail_url = document.selectFirst(".f-poster img, .mov-poster img")?.let {
                it.absUrl("data-src").ifBlank { it.absUrl("src") }
            } ?: ""
            status = SAnime.COMPLETED
            fetch_type = FetchType.Episodes
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val filmData = document.selectFirst("#film-data, div[data-newsid]:has(.f-player), div[id*=film]")
        val serieData = document.selectFirst("#serie-data, div[data-newsid]:has(.player-serie), div[id*=serie]")
        val anyNewsId = document.selectFirst("[data-newsid]")?.attr("data-newsid")
            ?: document.selectFirst("input[name=news_id]")?.attr("value")

        if (filmData != null || (serieData == null && anyNewsId != null)) {
            val newsId = filmData?.attr("data-newsid")?.ifBlank { null } ?: anyNewsId ?: ""
            val apiUrl = "$baseUrl/engine/ajax/film_api.php?id=$newsId"
            val apiRes = try {
                client.newCall(GET(apiUrl, headers)).execute().body.string()
            } catch (_: Exception) {
                ""
            }

            if (apiRes.isNotBlank()) {
                val json = try { JSONObject(apiRes) } catch (_: Exception) { null }
                val players = json?.optJSONObject("players")

                if (players != null) {
                    val vfUrls = mutableListOf<String>()
                    val vostfrUrls = mutableListOf<String>()

                    for (key in players.keys()) {
                        if (key == "premium") continue
                        val pData = players.optJSONObject(key) ?: continue
                        val hosterName = mapPlayerKeyToName(key)
                        val vfUrl = pData.optString("vff").ifBlank {
                            pData.optString("vf").ifBlank {
                                pData.optString("default").ifBlank {
                                    pData.optString("vfq")
                                }
                            }
                        }
                        if (vfUrl.isNotBlank()) vfUrls.add("(VF) $hosterName|$vfUrl")

                        val vostfrUrl = pData.optString("vostfr")
                        if (vostfrUrl.isNotBlank()) vostfrUrls.add("(VOSTFR) $hosterName|$vostfrUrl")
                    }

                    val allMovieUrls = mutableListOf<String>()
                    val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
                    if (prefVoice == "vostfr") {
                        allMovieUrls.addAll(vostfrUrls)
                        allMovieUrls.addAll(vfUrls)
                    } else {
                        allMovieUrls.addAll(vfUrls)
                        allMovieUrls.addAll(vostfrUrls)
                    }

                    if (allMovieUrls.isNotEmpty()) {
                        episodeList.add(
                            SEpisode.create().apply {
                                name = "Film complet"
                                episode_number = 1F
                                scanlator = when {
                                    vfUrls.isNotEmpty() && vostfrUrls.isNotEmpty() -> "VF & VOSTFR"
                                    vfUrls.isNotEmpty() -> "VF"
                                    vostfrUrls.isNotEmpty() -> "VOSTFR"
                                    else -> null
                                }
                                url = allMovieUrls.joinToString(",")
                            }
                        )
                    }
                }
            }
        } else if (serieData != null) {
            val newsId = serieData.attr("data-newsid").ifBlank { anyNewsId ?: "" }
            val epUrl = "$baseUrl/ep-data.php?id=$newsId&format=js"
            val epRes = try {
                client.newCall(GET(epUrl, headers)).execute().body.string()
            } catch (_: Exception) {
                ""
            }

            if (epRes.isNotBlank()) {
                val json = try { JSONObject(epRes) } catch (_: Exception) { null }
                val vfObj = json?.optJSONObject("vf")
                val vostfrObj = json?.optJSONObject("vostfr")

                if (vfObj != null || vostfrObj != null) {
                    val allKeys = mutableSetOf<String>()
                    vfObj?.keys()?.forEach { allKeys.add(it) }
                    vostfrObj?.keys()?.forEach { allKeys.add(it) }

                    val sortedEpKeys = allKeys
                        .filter { it.toIntOrNull() != null }
                        .sortedBy { it.toIntOrNull() ?: 0 }

                    val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT

                    for (epKey in sortedEpKeys) {
                        val vfUrls = mutableListOf<String>()
                        val vostfrUrls = mutableListOf<String>()

                        val vfData = vfObj?.optJSONObject(epKey)
                        if (vfData != null) {
                            for (pKey in vfData.keys()) {
                                if (pKey == "premium") continue
                                val u = vfData.optString(pKey)
                                if (u.isNotBlank()) {
                                    val hosterName = mapPlayerKeyToName(pKey)
                                    vfUrls.add("(VF) $hosterName|$u")
                                }
                            }
                        }

                        val vostfrData = vostfrObj?.optJSONObject(epKey)
                        if (vostfrData != null) {
                            for (pKey in vostfrData.keys()) {
                                if (pKey == "premium") continue
                                val u = vostfrData.optString(pKey)
                                if (u.isNotBlank()) {
                                    val hosterName = mapPlayerKeyToName(pKey)
                                    vostfrUrls.add("(VOSTFR) $hosterName|$u")
                                }
                            }
                        }

                        val epUrls = mutableListOf<String>()
                        if (prefVoice == "vostfr") {
                            epUrls.addAll(vostfrUrls)
                            epUrls.addAll(vfUrls)
                        } else {
                            epUrls.addAll(vfUrls)
                            epUrls.addAll(vostfrUrls)
                        }

                        if (epUrls.isNotEmpty()) {
                            val epNum = epKey.toFloatOrNull() ?: 0F
                            episodeList.add(
                                SEpisode.create().apply {
                                    name = "Épisode $epKey"
                                    episode_number = epNum
                                    scanlator = when {
                                        vfUrls.isNotEmpty() && vostfrUrls.isNotEmpty() -> "VF & VOSTFR"
                                        vfUrls.isNotEmpty() -> "VF"
                                        vostfrUrls.isNotEmpty() -> "VOSTFR"
                                        else -> null
                                    }
                                    url = epUrls.joinToString(",")
                                }
                            )
                        }
                    }
                }
            }
        }

        // Fallback: If no episodes were extracted from APIs, check for iframes on the page
        if (episodeList.isEmpty()) {
            val iframes = document.select("iframe[src]").map { it.attr("src") }.filter { it.startsWith("http") }
            if (iframes.isNotEmpty()) {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Film / Épisode complet"
                        episode_number = 1F
                        url = iframes.joinToString(",")
                    }
                )
            } else {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Épisode complet"
                        episode_number = 1F
                        url = response.request.url.encodedPath
                    }
                )
            }
        }

        return episodeList.sortedBy { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val entries = if (episode.url.startsWith("/")) {
            val targetUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
            try {
                val doc = client.newCall(GET(targetUrl, headers)).execute().asJsoup()
                doc.select("iframe[src]").map { it.attr("src") }.filter { it.startsWith("http") }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            episode.url.split(",").filter { it.isNotBlank() }
        }

        val hosters = entries.mapNotNull { entry ->
            val (name, url) = if (entry.contains("|")) {
                val parts = entry.split("|", limit = 2)
                parts[0] to parts[1]
            } else {
                guessHosterName(entry) to entry
            }
            if (url.isNotBlank()) {
                Hoster(
                    hosterUrl = url,
                    hosterName = name,
                )
            } else null
        }

        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
        return when (prefVoice) {
            "vostfr" -> hosters.sortedByDescending { it.hosterName.contains("VOSTFR") }
            "vf" -> hosters.sortedByDescending { it.hosterName.contains("VF") }
            else -> hosters
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val resolvedUrl = resolveRedirectIfNeeded(hoster.hosterUrl)
            val resolvedHoster = Hoster(
                hosterUrl = resolvedUrl,
                hosterName = hoster.hosterName,
                internalData = hoster.internalData,
                lazy = hoster.lazy,
            )
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

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    private fun mapPlayerKeyToName(key: String): String {
        return when (key.lowercase()) {
            "vidzy" -> "Vidzy"
            "uqload" -> "Uqload"
            "voe" -> "VOE"
            "filmoon", "filemoon" -> "Filemoon"
            "dood", "doodstream", "netu" -> "Doodstream"
            "streamwish" -> "StreamWish"
            "vidmoly" -> "VidMoly"
            "sibnet" -> "Sibnet"
            "sendvid" -> "Sendvid"
            else -> key.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

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
        CategoryFilter(),
        GenreFilter(),
    )

    private class CategoryFilter : UriPartFilter(
        "Catégorie",
        arrayOf(
            Pair("Tous", ""),
            Pair("Films", "/films"),
            Pair("Séries", "/series"),
            Pair("Films VF", "/films-vf"),
            Pair("Films VOSTFR", "/films-vostfr"),
            Pair("Séries VF", "/series-vf"),
            Pair("Séries VOSTFR", "/series-vostfr"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("Tous", ""),
            Pair("Action", "/genre/action"),
            Pair("Animation", "/genre/animation"),
            Pair("Arts Martiaux", "/genre/arts-martiaux"),
            Pair("Aventure", "/genre/aventure"),
            Pair("Biopic", "/genre/biopic"),
            Pair("Comédie", "/genre/comedie"),
            Pair("Comédie dramatique", "/genre/comedie-dramatique"),
            Pair("Comédie musicale", "/genre/comedie-musicale"),
            Pair("Documentaire", "/genre/documentaire"),
            Pair("Drame", "/genre/drame"),
            Pair("Epouvante-horreur", "/genre/epouvante-horreur"),
            Pair("Espionnage", "/genre/espionnage"),
            Pair("Famille", "/genre/famille"),
            Pair("Fantastique", "/genre/fantastique"),
            Pair("Guerre", "/genre/guerre"),
            Pair("Historique", "/genre/historique"),
            Pair("Judiciaire", "/genre/judiciaire"),
            Pair("Médical", "/genre/medical"),
            Pair("Musical", "/genre/musical"),
            Pair("Policier", "/genre/policier"),
            Pair("Romance", "/genre/romance"),
            Pair("Science-Fiction", "/genre/science-fiction"),
            Pair("Thriller", "/genre/thriller"),
            Pair("Western", "/genre/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence de langue (VF par défaut)"
            entries = arrayOf("VF uniquement", "VF et VOSTFR", "VOSTFR uniquement")
            entryValues = arrayOf("vf", "both", "vostfr")
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_DOMAIN = "https://french-stream.net"
        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_DEFAULT = "vf"
    }
}
