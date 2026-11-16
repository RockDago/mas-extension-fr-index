package eu.kanade.tachiyomi.animeextension.fr.voiranime

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
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.smoothpreextractor.SmoothpreExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidzyextractor.VidzyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import extensionsfr.utils.AnimeDomainSource
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class VoirAnime : AnimeDomainSource() {

    override val name = "VoirAnime"

    override val lang = "fr"

    override val supportsLatest = true

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

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/catalogue/?sort=views&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("a.card-base").mapNotNull { card ->
            val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = card.attr("href").trim()
            val img = card.selectFirst("img.card-image")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            SAnime.create().apply {
                this.title = title
                this.setUrlWithoutDomain(href)
                this.thumbnail_url = img?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
                this.fetch_type = FetchType.Episodes
            }
        }

        val hasNextPage = document.selectFirst("link[rel=next], .pagination a:contains(›)") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/catalogue/?sort=recent&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("search", query.trim())
            }
            for (filter in filters) {
                when (filter) {
                    is LangFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("langue", filter.toUriPart())
                    is TypeFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("type", filter.toUriPart())
                    is GenreFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("genre", filter.toUriPart())
                    is SortFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("sort", filter.toUriPart())
                    else -> {}
                }
            }
        }.build().toString()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        checkBaseUrl()
        return super.getAnimeDetails(anime).apply {
            fetch_type = FetchType.Episodes
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".hero-title")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: ""
            description = document.selectFirst("#synopsisText")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            genre = document.select(".hero-genres .genre-link").eachText().joinToString(", ")
            val backdrop = document.selectFirst(".hero-backdrop img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            thumbnail_url = backdrop?.ifBlank { poster } ?: poster
            status = SAnime.UNKNOWN
            fetch_type = FetchType.Episodes
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        checkBaseUrl()
        val fullUrl = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(fullUrl, headers)).await()
        val doc = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val seasonsGrid = doc.select(".seasons-grid a.season-card")
        val filmPlayer = doc.selectFirst("#film-player, .film-player-container")

        // 1. Film
        if (seasonsGrid.isEmpty() && filmPlayer != null) {
            val script = doc.selectFirst("script:containsData(filmUrls)")?.data() ?: ""
            val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
            val vfUrl = Regex(""""vf"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")
            val vostfrUrl = Regex(""""vostfr"\s*:\s*"([^"]+)"""").find(script)?.groupValues?.get(1)?.replace("\\/", "/")

            val hasVf = !vfUrl.isNullOrBlank() || doc.selectFirst(".film-language-selector .lang-btn.vf") != null
            val hasVostfr = !vostfrUrl.isNullOrBlank() || doc.selectFirst(".film-language-selector .lang-btn.vostfr") != null

            val cleanAnimeUrl = anime.url.substringBefore('#')

            if (hasVostfr && prefVoice != "vf") {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Film complet VOSTFR"
                        episode_number = 1F
                        scanlator = "VOSTFR"
                        url = "$cleanAnimeUrl#vostfr"
                    },
                )
            }
            if (hasVf && prefVoice != "vostfr") {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Film complet VF"
                        episode_number = if (episodeList.isEmpty()) 1F else 2F
                        scanlator = "VF"
                        url = "$cleanAnimeUrl#vf"
                    },
                )
            }
            if (episodeList.isEmpty()) {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Film complet"
                        episode_number = 1F
                        url = cleanAnimeUrl
                    },
                )
            }
            return episodeList
        }

        // 2. Series with seasons
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
        if (seasonsGrid.isNotEmpty()) {
            val seasonsEpisodes = seasonsGrid.parallelMap { seasonEl ->
                val seasonHref = seasonEl.attr("href").trim()
                val seasonTitle = seasonEl.selectFirst(".season-title")?.text()?.trim()
                    ?: seasonEl.attr("title").trim()
                val sUrl = if (seasonHref.startsWith("http")) seasonHref else "$baseUrl$seasonHref"
                fetchSeasonEpisodes(sUrl, seasonTitle, prefVoice)
            }.flatten()
            episodeList.addAll(seasonsEpisodes)
        } else {
            episodeList.addAll(parseEpisodesFromDoc(doc, "Saison 1", prefVoice))
        }

        return episodeList.sortedBy { it.episode_number }
    }

    private fun fetchSeasonEpisodes(seasonUrl: String, seasonTitle: String, prefVoice: String): List<SEpisode> {
        return try {
            val response = client.newCall(GET(seasonUrl, headers)).execute()
            val doc = response.asJsoup()
            parseEpisodesFromDoc(doc, seasonTitle, prefVoice)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseEpisodesFromDoc(doc: Document, seasonTitle: String, prefVoice: String): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val seasonNum = Regex("""(?:saison|season)[^\d]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        doc.select("a.episode-card").forEach { card ->
            val baseHref = card.attr("href").trim()
            val epText = card.selectFirst(".episode-number, .episode-title")?.text()?.trim() ?: ""
            val epNum = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(baseHref)?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("""\d+""").find(epText)?.value?.toFloatOrNull()
                ?: 1F

            val rawLangs = card.attr("data-langs").lowercase().split(" ")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty {
                    card.select(".lang-badge").map { it.text().trim().lowercase() }
                }

            val hasVf = rawLangs.contains("vf")
            val hasVostfr = rawLangs.contains("vostfr") || (!hasVf && rawLangs.isEmpty())

            val vfHref = baseHref.replace(Regex("""/(vostfr|va|vkr)/?$"""), "/vf/")
            val vostfrHref = baseHref.replace(Regex("""/(vf|va|vkr)/?$"""), "/vostfr/")

            val cleanTitle = if (epText.isNotBlank()) epText else "Épisode ${epNum.toInt()}"

            when (prefVoice) {
                "vostfr" -> {
                    if (hasVostfr) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VOSTFR"
                                episode_number = (seasonNum * 1000F) + epNum
                                scanlator = "VOSTFR"
                                url = vostfrHref
                            },
                        )
                    } else if (hasVf) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VF"
                                episode_number = (seasonNum * 1000F) + epNum
                                scanlator = "VF"
                                url = vfHref
                            },
                        )
                    }
                }
                "vf" -> {
                    if (hasVf) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VF"
                                episode_number = (seasonNum * 1000F) + epNum
                                scanlator = "VF"
                                url = vfHref
                            },
                        )
                    } else if (hasVostfr) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VOSTFR"
                                episode_number = (seasonNum * 1000F) + epNum
                                scanlator = "VOSTFR"
                                url = vostfrHref
                            },
                        )
                    }
                }
                else -> { // "both"
                    if (hasVostfr) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VOSTFR"
                                episode_number = (seasonNum * 1000F) + epNum
                                scanlator = "VOSTFR"
                                url = vostfrHref
                            },
                        )
                    }
                    if (hasVf) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "$seasonTitle $cleanTitle VF"
                                episode_number = (seasonNum * 1000F) + epNum + 0.5F
                                scanlator = "VF"
                                url = vfHref
                            },
                        )
                    }
                }
            }
        }
        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    private class FilemoonWrapper(private val client: OkHttpClient) : Extractor() {
        override val name = "Filemoon"
        override val supportedUrls = listOf("filemoon.sx", "filemoon.to", "filemoon.nl", "mxzhxco8dft.com", "kokoflix.lol", "trakx.lol")
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

    override val extractorList: List<Extractor> = listOf(
        VidMolyExtractor(client),
        VoeExtractor(client, headers),
        SmoothpreExtractor(client),
        VidHideExtractor(client, headers),
        UqloadExtractor(client),
        SendvidExtractor(client, headers),
        SibnetExtractor(client),
        VidzyExtractor(client),
        UpstreamExtractor(client),
        DoodWrapper(client),
        StreamWishWrapper(client, headers),
        FilemoonWrapper(client),
    )

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val cleanUrl = episode.url.substringBefore('#')
        val targetUrl = if (cleanUrl.startsWith("http")) cleanUrl else "$baseUrl$cleanUrl"
        val doc = try {
            client.newCall(GET(targetUrl, headers)).await().asJsoup()
        } catch (_: Exception) {
            return emptyList()
        }

        val hosters = mutableListOf<Hoster>()
        val seenUrls = mutableSetOf<String>()

        // Check if Film script has filmUrls
        val filmScript = doc.selectFirst("script:containsData(filmUrls)")?.data() ?: ""
        if (filmScript.contains("filmUrls")) {
            val isVf = episode.url.contains("#vf") || episode.scanlator.equals("VF", ignoreCase = true)
            val isVostfr = episode.url.contains("#vostfr") || episode.scanlator.equals("VOSTFR", ignoreCase = true)
            val vfUrl = Regex(""""vf"\s*:\s*"([^"]+)"""").find(filmScript)?.groupValues?.get(1)?.replace("\\/", "/")
            val vostfrUrl = Regex(""""vostfr"\s*:\s*"([^"]+)"""").find(filmScript)?.groupValues?.get(1)?.replace("\\/", "/")

            val selectedUrl = when {
                isVf && !vfUrl.isNullOrBlank() -> vfUrl
                isVostfr && !vostfrUrl.isNullOrBlank() -> vostfrUrl
                !vostfrUrl.isNullOrBlank() -> vostfrUrl
                !vfUrl.isNullOrBlank() -> vfUrl
                else -> null
            }
            if (!selectedUrl.isNullOrBlank() && seenUrls.add(selectedUrl)) {
                hosters.add(Hoster(hosterUrl = selectedUrl, hosterName = guessHosterName(selectedUrl)))
            }
        }

        // 1. Check select#epLecteurSelect option[value^=http]
        doc.select("#epLecteurSelect option").forEach { opt ->
            val url = opt.attr("value").trim()
            val label = opt.text().trim()
            if (url.startsWith("http") && seenUrls.add(url)) {
                hosters.add(Hoster(hosterUrl = url, hosterName = guessHosterName(url, label)))
            }
        }

        // 2. Check vrReaderButtons
        doc.select("#vrReaderButtons button[data-url], [data-player-url]").forEach { btn ->
            val url = btn.attr("data-url").ifBlank { btn.attr("data-player-url") }.trim()
            val label = btn.text().trim()
            if (url.startsWith("http") && seenUrls.add(url)) {
                hosters.add(Hoster(hosterUrl = url, hosterName = guessHosterName(url, label)))
            }
        }

        // 3. Check video wrapper iframes
        doc.select(".video-wrapper iframe[src], #videoPlayer[src]").forEach { iframe ->
            val url = iframe.attr("src").trim()
            if (url.startsWith("http") && !url.contains("about:blank") && seenUrls.add(url)) {
                hosters.add(Hoster(hosterUrl = url, hosterName = guessHosterName(url)))
            }
        }

        // 4. Check inline scripts for embed links
        val allScripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val embedRegex = Regex("""https?://[a-zA-Z0-9.-]+/(?:embed|e|v)/[a-zA-Z0-9_-]+(?:\.html)?""")
        embedRegex.findAll(allScripts).forEach { match ->
            val u = match.value
            if (seenUrls.add(u)) {
                hosters.add(Hoster(hosterUrl = u, hosterName = guessHosterName(u)))
            }
        }

        val prefHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefHoster, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val extractor = extractorList.firstOrNull {
                it.name.equals(hoster.hosterName, ignoreCase = true) || it.supportsUrl(hoster.hosterUrl)
            }
            extractor?.videosFromHoster(hoster)?.map {
                it.copy(preferred = isPreferred(hoster, it))
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun isPreferred(hoster: Hoster, video: Video): Boolean {
        val pref = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hoster.hosterName.contains(pref, ignoreCase = true) || video.videoTitle.contains(pref, ignoreCase = true)
    }

    private fun guessHosterName(url: String, fallback: String = ""): String {
        val lower = url.lowercase()
        return when {
            "ansembed" in lower || "vidmoly" in lower -> "VidMoly"
            "voe.sx" in lower || "jeremyparticipantanything" in lower || "susanexpectcare" in lower -> "VOE"
            "smoothpre" in lower || "movearnpre" in lower || "morencius" in lower -> "Smoothpre"
            "streamwish" in lower || "wishembed" in lower || "strwish" in lower -> "StreamWish"
            "vidhide" in lower -> "VidHide"
            "filemoon" in lower || "grandline" in lower -> "Filemoon"
            "uqload" in lower -> "Uqload"
            "sendvid" in lower -> "Sendvid"
            "sibnet" in lower -> "Sibnet"
            "dood" in lower || "playmogo" in lower -> "Doodstream"
            "vidzy" in lower -> "Vidzy"
            "upstream" in lower -> "Upstream"
            fallback.isNotBlank() && !fallback.startsWith("http", ignoreCase = true) -> fallback
            else -> extractorList.firstOrNull { it.supportsUrl(url) }?.name ?: "Lecteur"
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        LangFilter(),
        TypeFilter(),
        GenreFilter(),
        SortFilter(),
    )

    private class LangFilter : UriPartFilter(
        "Langue",
        arrayOf(
            Pair("Toutes les langues", ""),
            Pair("VF", "VF"),
            Pair("VOSTFR", "VOSTFR"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("Tous les types", ""),
            Pair("Série", "Série"),
            Pair("Film", "Film"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("Tous les genres", ""),
            Pair("Action", "9"),
            Pair("Animes", "1"),
            Pair("Aventure", "13"),
            Pair("Comédie", "11"),
            Pair("Crime", "15"),
            Pair("Drame", "12"),
            Pair("Famille", "16"),
            Pair("Fantastique", "14"),
            Pair("Films", "2"),
            Pair("Guerre", "17"),
            Pair("Horreur", "22"),
            Pair("Mystère", "18"),
            Pair("Romance", "20"),
            Pair("Scans", "50"),
            Pair("Science-Fiction", "19"),
            Pair("Thriller", "21"),
            Pair("Top Animes", "3"),
            Pair("Voirdrama", "48"),
        ),
    )

    private class SortFilter : UriPartFilter(
        "Trier par",
        arrayOf(
            Pair("Plus récents", "recent"),
            Pair("A → Z", "az"),
            Pair("Les plus vus", "views"),
        ),
    )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence de langue (VF et VOSTFR par défaut)"
            entries = arrayOf("VF et VOSTFR", "VOSTFR uniquement", "VF uniquement")
            entryValues = arrayOf("both", "vostfr", "vf")
            setDefaultValue(PREF_VOICES_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Hébergeur préféré"
            entries = arrayOf("VidMoly", "VOE", "Smoothpre", "StreamWish", "VidHide", "Filemoon", "Uqload", "Sendvid", "Sibnet", "Doodstream")
            entryValues = arrayOf("VidMoly", "VOE", "Smoothpre", "StreamWish", "VidHide", "Filemoon", "Uqload", "Sendvid", "Sibnet", "Doodstream")
            setDefaultValue(PREF_HOSTER_DEFAULT)
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
        private const val DEFAULT_DOMAIN = "https://voiranime.rip"

        private const val PREF_VOICES_KEY = "preferred_voices"
        private const val PREF_VOICES_DEFAULT = "both"

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "VidMoly"
    }
}
