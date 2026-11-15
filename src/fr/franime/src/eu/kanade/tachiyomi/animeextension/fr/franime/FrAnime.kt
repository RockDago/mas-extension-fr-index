package eu.kanade.tachiyomi.animeextension.fr.franime

import android.util.Log
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.AnimeSeasons
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Animes
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Images
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Season
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import extensionsfr.utils.AnimeDomainSource
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.experimental.xor
import kotlin.io.encoding.Base64
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class FrAnime : AnimeDomainSource() {

    override val name = "FRAnime"

    override var baseUrl by LazyMutable {
        preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
    }

    private val baseApiUrl by lazy { baseUrl.replaceFirst("://", "://api.") + "/api" }

    override val lang = "fr"

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private val json by lazy { Json(Injekt.get()) { coerceInputValues = true } }

    private val database by lazy {
        checkBaseUrl()
        client.newCall(GET("$baseApiUrl/animes/", headers)).execute()
            .body.string()
            .let { json.decodeFromString<Animes>(it) }
    }

    private val watch2Headers = headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .build()

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int) =
        database.sortedByDescending { it.note }
            .chunked(15).let {
                AnimesPage(it[page - 1].map { it.toSAnime() }, it.size > page)
            }

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage =
        throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): AnimesPage {
        val pages = database.filter {
            it.title.contains(query, true)
                    || it.originalTitle.contains(query, true)
                    || it.titlesAlt.en?.contains(query, true) == true
                    || it.titlesAlt.enJp?.contains(query, true) == true
                    || it.titlesAlt.jaJp?.contains(query, true) == true
                    || titleToUrl(it.originalTitle).contains(query)
        }.chunked(15)
        return AnimesPage(pages[page - 1].map { it.toSAnime() }, pages.size > page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage =
        throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw UnsupportedOperationException()

    // =========================== Anime Details ===========================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = (baseUrl + anime.url).toHttpUrl()
        val animeId = url.queryParameter("anime_id")?.toLong() ?: throw IllegalArgumentException("getAnimeDetails missing anime_id")
        val dbAnime = database.first{ it.id == animeId }
        return if (anime.fetch_type == FetchType.Seasons) {
            dbAnime.toSAnime()
        } else {
            val seasonNum = url.queryParameter("s")?.toInt() ?: throw IllegalArgumentException("getAnimeDetails missing s")
            dbAnime.seasons[seasonNum - 1].toSAnime(dbAnime, seasonNum)
        }
    }

    override fun animeDetailsParse(response: Response): SAnime =
        throw UnsupportedOperationException()

    private fun Anime.toSAnime(): SAnime = SAnime.create().apply {
        this@apply.title = this@toSAnime.title.ifEmpty { this@toSAnime.originalTitle }
        this@apply.thumbnail_url = this@toSAnime.poster.ifEmpty { this@toSAnime.banner }
        this@apply.background_url = this@toSAnime.banner.ifEmpty { this@toSAnime.poster }
        this@apply.genre = this@toSAnime.genres.joinToString()
        this@apply.status = this@toSAnime.status.toSAnimeStatus()
        this@apply.description = this@toSAnime.description + "\nNote: ${this@toSAnime.note}"
        setUrlWithoutDomain("/anime/${titleToUrl(this@toSAnime.originalTitle)}?anime_id=${this@toSAnime.id}")
        this@apply.fetch_type = FetchType.Episodes
        this@apply.initialized = true
    }

    // =========================== Seasons ============================
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val animeId = (baseUrl + anime.url).toHttpUrl().queryParameter("anime_id")?.toLong()
            ?: throw IllegalArgumentException("getSeasonList missing anime_id")
        val dbAnime = database.first { it.id == animeId }
        val seasons = dbAnime.seasons.mapIndexed { index, season ->
            season.toSAnime(dbAnime, index + 1)
        }
        return seasons
    }

    override fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException()

    private fun Season.toSAnime(anime: Anime, seasonNumber: Int = 1) = anime.toSAnime().apply {
        this@apply.title += " " + this@toSAnime.title.ifEmpty { "Saison $seasonNumber" }
        this@apply.status = anime.status.toSAnimeStatus(seasonNumber, anime.seasons.size)
        setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}?anime_id=${anime.id}&s=${seasonNumber}")
        this@apply.fetch_type = FetchType.Episodes
        runCatching {
            val season = client.newCall(GET("$baseApiUrl/anime-seasons/${anime.id}/$seasonNumber")).execute().parseAs<AnimeSeasons>()
            client.newCall(GET("https://kitsu.io/api/edge/anime/${season.kitsuId}?fields[anime]=posterImage,coverImage"))
                .execute().parseAs<Images>()
        }.onSuccess {
            this@apply.thumbnail_url = it.data.attributes.posterImage.original
            this@apply.background_url = it.data.attributes.coverImage.original
        }
    }

    // ============================== Episodes ==============================
    @OptIn(ExperimentalTime::class)
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val animeId = url.queryParameter("anime_id")?.toLong()
            ?: throw IllegalArgumentException("getEpisodeList missing anime_id")
        val seasonParam = url.queryParameter("s")?.toIntOrNull()
        val dbAnime = database.first { it.id == animeId }

        val seasonsToProcess = if (seasonParam != null && seasonParam in 1..dbAnime.seasons.size) {
            listOf(seasonParam to dbAnime.seasons[seasonParam - 1])
        } else {
            dbAnime.seasons.mapIndexed { idx, s -> (idx + 1) to s }
        }

        val allEpisodes = mutableListOf<SEpisode>()

        for ((sNum, seasonObj) in seasonsToProcess) {
            val meta = runCatching {
                client.newCall(GET("$baseApiUrl/anime-seasons/$animeId/$sNum")).execute().parseAs<AnimeSeasons>()
            }.getOrNull()

            val eps = seasonObj.episodes.mapIndexed { index, episode ->
                SEpisode.create().apply {
                    val epNum = index + 1
                    val baseAnimePath = "/anime/${titleToUrl(dbAnime.originalTitle)}?anime_id=$animeId"
                    setUrlWithoutDomain("$baseAnimePath&s=$sNum&ep=$epNum")

                    val prefix = if (dbAnime.seasons.size > 1 && seasonParam == null) "S$sNum " else ""
                    name = "${prefix}Épisode $epNum"
                    episode_number = if (dbAnime.seasons.size > 1 && seasonParam == null) {
                        sNum * 1000F + epNum
                    } else {
                        epNum.toFloat()
                    }

                    val epMeta = meta?.episodes?.firstOrNull { it.number == epNum }
                    if (epMeta != null) {
                        if (epMeta.airDate.isNotEmpty()) {
                            date_upload = runCatching {
                                Instant.parse("${epMeta.airDate}T00:00:00Z").toEpochMilliseconds()
                            }.getOrDefault(0L)
                        }
                        if (epMeta.thumbnail.isNotEmpty()) preview_url = epMeta.thumbnail
                    }
                    val title = listOf(epMeta?.title, episode.title).firstOrNull { !it.isNullOrBlank() }
                    if (!title.isNullOrBlank()) name += ": $title"
                }
            }
            allEpisodes.addAll(eps)
        }

        return allEpisodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException()

    // =========================== Hosters ============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val url = (baseUrl + episode.url).toHttpUrl()
        Log.d(name, (baseUrl + episode.url))
        val s = url.queryParameter("s")?.toInt()
            ?: throw IllegalArgumentException("getHosterList missing s")
        val epNum = url.queryParameter("ep")?.toInt()
            ?: throw IllegalArgumentException("getHosterList missing ep")
        val animeId = url.queryParameter("anime_id")?.toLong()
            ?: throw IllegalArgumentException("getHosterList missing anime_id")
        val episode = database.first { it.id == animeId }.seasons[s - 1].episodes[epNum - 1]
        val hosters = mapOf(
            "vo" to episode.languages.vo,
            "vf" to episode.languages.vf
        ).flatMap { (lang, epLang) ->
            epLang.players.mapIndexed { idx, name ->
                Hoster(
                    hosterUrl = "/anime/$animeId/${s - 1}/${epNum - 1}/${lang}/${idx}",
                    hosterName = "$name (${lang.uppercase()})",
                )
            }
        }
        return hosters
    }

    override fun hosterListParse(response: Response): List<Hoster> =
        throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private class FilemoonWrapper(private val client: OkHttpClient) : Extractor() {
        override val name = "Filemoon"
        override val supportedUrls = listOf("filemoon.sx", "filemoon.to", "filemoon.nl", "mxzhxco8dft.com")
        private val extractor by lazy { FilemoonExtractor(client) }
        override fun videosFromHoster(hoster: Hoster): List<Video> {
            return extractor.videosFromUrl(hoster.hosterUrl, "${hoster.hosterName} - ")
        }
    }

    override val extractorList: List<Extractor> = listOf(
        VidMolyExtractor(client),
        SibnetExtractor(client),
        SendvidExtractor(client, headers),
        FilemoonWrapper(client),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val enc = client.newCall(GET(baseApiUrl + hoster.hosterUrl, watch2Headers))
                .awaitSuccess().body.use { it.string().toHttpUrl() }
            val b = enc.queryParameter("b") ?: return emptyList()
            val m = enc.queryParameter("m") ?: return emptyList()
            val playerUrl = decodeWatch2(b, m)
            val extractor = extractorList.firstOrNull { it.supportsUrl(playerUrl) } ?: return emptyList()
            extractor.videosFromUrl(playerUrl)
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

    // c'est de bonne guerre, venez on collabore et vous pouvez drop votre app en webview bancale?
    private fun decodeWatch2(b: String, m: String): String {
        val key = Base64.UrlSafe.decode(m)
            .decodeToString()
            .let { it.substring(4 ..< it.length - 7) }
            .fold(0) { key, c -> key xor c.code }
            .toByte()
        val url = Base64.UrlSafe.decode(b)
            .decodeToString()
            .hexToByteArray()
            .also { it.forEachIndexed { i, b -> it[i] = b xor key } }
            .decodeToString()
        return url
    }

    // ============================= Utilities ==============================
    private val titleRegex by lazy { Regex("[^A-Za-z0-9 ]") }

    private fun titleToUrl(title: String) =
        titleRegex.replace(title, "").replace(" ", "-").lowercase()

    private fun String.toSAnimeStatus(season: Int = 1, seasonCount: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (this.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val DEFAULT_DOMAIN = "https://franime.fr"
    }
}
