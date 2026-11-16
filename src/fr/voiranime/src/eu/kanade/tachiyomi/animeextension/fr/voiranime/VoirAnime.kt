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

            val scanlatorText = when {
                hasVf && hasVostfr -> "VF & VOSTFR"
                hasVf -> "VF"
                hasVostfr -> "VOSTFR"
                else -> null
            }

            val filmUrl = when {
                prefVoice == "vf" && hasVf -> "$cleanAnimeUrl#vf"
                prefVoice == "vostfr" && hasVostfr -> "$cleanAnimeUrl#vostfr"
                hasVf && hasVostfr -> "$cleanAnimeUrl#vostfr|$cleanAnimeUrl#vf"
                hasVf -> "$cleanAnimeUrl#vf"
                else -> "$cleanAnimeUrl#vostfr"
            }

            episodeList.add(
                SEpisode.create().apply {
                    name = "Film complet"
                    episode_number = 1F
                    scanlator = scanlatorText
                    url = filmUrl
                },
            )
            return episodeList
        }

        // 2. Series with seasons
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
        if (seasonsGrid.isNotEmpty()) {
            val seasonData = seasonsGrid.parallelMap { seasonEl ->
                val seasonHref = seasonEl.attr("href").trim()
                val seasonTitle = seasonEl.selectFirst(".season-title")?.text()?.trim()
                    ?: seasonEl.attr("title").trim()
                val sUrl = if (seasonHref.startsWith("http")) seasonHref else "$baseUrl$seasonHref"
                val sDoc = try {
                    client.newCall(GET(sUrl, headers)).await().asJsoup()
                } catch (_: Exception) {
                    null
                }
                Pair(seasonTitle, sDoc)
            }

            var globalEpIndex = 1
            for ((seasonTitle, sDoc) in seasonData) {
                if (sDoc == null) continue
                val sEpisodes = parseEpisodesFromDoc(
                    sDoc,
                    seasonTitle = if (seasonsGrid.size > 1) seasonTitle else null,
                    prefVoice = prefVoice,
                    startIndex = globalEpIndex,
                )
                episodeList.addAll(sEpisodes)
                globalEpIndex += sEpisodes.size
            }
        } else {
            episodeList.addAll(parseEpisodesFromDoc(doc, null, prefVoice, 1))
        }

        return episodeList.reversed()
    }

    private fun parseEpisodesFromDoc(
        doc: Document,
        seasonTitle: String?,
        prefVoice: String,
        startIndex: Int,
    ): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val cards = doc.select("a.episode-card")

        cards.forEachIndexed { i, card ->
            val baseHref = card.attr("href").trim()
            val epText = card.selectFirst(".episode-number, .episode-title")?.text()?.trim() ?: ""
            val epNum = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(baseHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                ?: (i + 1)

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

            val cleanTitle = if (epText.isNotBlank()) epText else "Épisode $epNum"
            val epName = if (!seasonTitle.isNullOrBlank()) {
                val cleanSeason = seasonTitle.replace(Regex("""\s+en streaming.*""", RegexOption.IGNORE_CASE), "").trim()
                "$cleanSeason $cleanTitle"
            } else {
                cleanTitle
            }

            val scanlatorText = when {
                hasVf && hasVostfr -> "VF & VOSTFR"
                hasVf -> "VF"
                hasVostfr -> "VOSTFR"
                else -> null
            }

            val epUrl = when {
                prefVoice == "vf" && hasVf -> vfHref
                prefVoice == "vostfr" && hasVostfr -> vostfrHref
                hasVf && hasVostfr -> "$vostfrHref|$vfHref"
                hasVf -> vfHref
                else -> vostfrHref
            }

            episodes.add(
                SEpisode.create().apply {
                    name = epName
                    episode_number = (startIndex + i).toFloat()
                    scanlator = scanlatorText
                    url = epUrl
                },
            )
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
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT
        val prefHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT

        val hosters = mutableListOf<Hoster>()
        val seenUrls = mutableSetOf<String>()

        // Check if episode is a Film with #vostfr / #vf
        val filmUrls = episode.url.split('|').filter { it.isNotBlank() }
        val isFilm = filmUrls.any { it.contains('#') }

        if (isFilm) {
            for (fUrl in filmUrls) {
                val cleanUrl = fUrl.substringBefore('#')
                val targetUrl = if (cleanUrl.startsWith("http")) cleanUrl else "$baseUrl$cleanUrl"
                val doc = try {
                    client.newCall(GET(targetUrl, headers)).await().asJsoup()
                } catch (_: Exception) {
                    continue
                }

                val filmScript = doc.selectFirst("script:containsData(filmUrls)")?.data() ?: ""
                val vfUrl = Regex(""""vf"\s*:\s*"([^"]+)"""").find(filmScript)?.groupValues?.get(1)?.replace("\\/", "/")
                val vostfrUrl = Regex(""""vostfr"\s*:\s*"([^"]+)"""").find(filmScript)?.groupValues?.get(1)?.replace("\\/", "/")

                val wantsVf = fUrl.contains("#vf") || episode.scanlator.equals("VF", ignoreCase = true)
                val wantsVostfr = fUrl.contains("#vostfr") || episode.scanlator.equals("VOSTFR", ignoreCase = true)

                if ((wantsVostfr || !wantsVf) && !vostfrUrl.isNullOrBlank() && seenUrls.add(vostfrUrl)) {
                    val rawName = guessHosterName(vostfrUrl)
                    hosters.add(Hoster(hosterUrl = vostfrUrl, hosterName = "(VOSTFR) $rawName"))
                }
                if ((wantsVf || !wantsVostfr) && !vfUrl.isNullOrBlank() && seenUrls.add(vfUrl)) {
                    val rawName = guessHosterName(vfUrl)
                    hosters.add(Hoster(hosterUrl = vfUrl, hosterName = "(VF) $rawName"))
                }
            }
            if (hosters.isNotEmpty()) {
                return sortHosters(hosters, prefVoice, prefHoster)
            }
        }

        // Standard episode URLs (may contain "vostfrHref|vfHref" or single URL)
        val targetUrls = episode.url.split('|').map { it.trim() }.filter { it.isNotBlank() }

        for (targetPath in targetUrls) {
            val cleanUrl = targetPath.substringBefore('#')
            val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "$baseUrl$cleanUrl"
            val doc = try {
                client.newCall(GET(fullUrl, headers)).await().asJsoup()
            } catch (_: Exception) {
                continue
            }

            val isVf = cleanUrl.contains("/vf/") || cleanUrl.endsWith("/vf")
            val isVostfr = cleanUrl.contains("/vostfr/") || cleanUrl.endsWith("/vostfr")
            val langPrefix = when {
                isVf -> "(VF)"
                isVostfr -> "(VOSTFR)"
                else -> ""
            }

            extractHostersFromDoc(doc, langPrefix, seenUrls, hosters)

            // If only one URL was provided and prefVoice is "both", check if page has switch to other language
            if (targetUrls.size == 1 && prefVoice == "both") {
                val otherLangLink = doc.selectFirst("a.lang-switch:not(.active), .film-language-selector a:not(.active)")?.attr("href")?.trim()
                if (!otherLangLink.isNullOrBlank()) {
                    val otherFullUrl = if (otherLangLink.startsWith("http")) otherLangLink else "$baseUrl$otherLangLink"
                    val otherDoc = try {
                        client.newCall(GET(otherFullUrl, headers)).await().asJsoup()
                    } catch (_: Exception) {
                        null
                    }
                    if (otherDoc != null) {
                        val otherIsVf = otherLangLink.contains("/vf/") || otherLangLink.endsWith("/vf")
                        val otherPrefix = if (otherIsVf) "(VF)" else "(VOSTFR)"
                        extractHostersFromDoc(otherDoc, otherPrefix, seenUrls, hosters)
                    }
                }
            }
        }

        return sortHosters(hosters, prefVoice, prefHoster)
    }

    private fun extractHostersFromDoc(
        doc: Document,
        langPrefix: String,
        seenUrls: MutableSet<String>,
        hosters: MutableList<Hoster>,
    ) {
        fun addHoster(url: String, label: String = "") {
            val trimmedUrl = url.trim()
            if (trimmedUrl.startsWith("http") && !trimmedUrl.contains("about:blank") && seenUrls.add(trimmedUrl)) {
                val rawName = guessHosterName(trimmedUrl, label)
                val finalName = if (langPrefix.isNotBlank()) "$langPrefix $rawName" else rawName
                hosters.add(Hoster(hosterUrl = trimmedUrl, hosterName = finalName))
            }
        }

        // 1. Check select#epLecteurSelect option[value^=http]
        doc.select("#epLecteurSelect option").forEach { opt ->
            addHoster(opt.attr("value"), opt.text())
        }

        // 2. Check vrReaderButtons
        doc.select("#vrReaderButtons button[data-url], [data-player-url]").forEach { btn ->
            val u = btn.attr("data-url").ifBlank { btn.attr("data-player-url") }
            addHoster(u, btn.text())
        }

        // 3. Check video wrapper iframes
        doc.select(".video-wrapper iframe[src], #videoPlayer[src]").forEach { iframe ->
            addHoster(iframe.attr("src"))
        }

        // 4. Check inline scripts for embed links or currentUrl
        val allScripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        Regex("""currentUrl\s*=\s*"([^"]+)"""").find(allScripts)?.groupValues?.get(1)?.replace("\\/", "/")?.let {
            addHoster(it)
        }
        val embedRegex = Regex("""https?://[a-zA-Z0-9.-]+/(?:embed|e|v)/[a-zA-Z0-9_-]+(?:\.html)?""")
        embedRegex.findAll(allScripts).forEach { match ->
            addHoster(match.value)
        }
    }

    private fun sortHosters(hosters: List<Hoster>, prefVoice: String, prefHoster: String): List<Hoster> {
        fun score(hoster: Hoster): Int {
            var s = 0
            val isPrefHoster = hoster.hosterName.contains(prefHoster, ignoreCase = true)
            val isVf = hoster.hosterName.contains("(VF)")
            val isVostfr = hoster.hosterName.contains("(VOSTFR)")

            if (isPrefHoster) s += 10
            when (prefVoice) {
                "vf" -> if (isVf) s += 20 else if (isVostfr) s += 5
                "vostfr" -> if (isVostfr) s += 20 else if (isVf) s += 5
                else -> if (isVostfr) s += 2 else if (isVf) s += 1
            }
            return s
        }
        return hosters.sortedByDescending { score(it) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val extractor = extractorList.firstOrNull {
                it.supportsUrl(hoster.hosterUrl) || hoster.hosterName.contains(it.name, ignoreCase = true)
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
