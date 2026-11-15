package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.DebugExtractor
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class FrenchAnime : DataLifeEngine(
    "French Anime",
    "fr",
) {

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

    override val categories = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Animes VF", "/animes-vf/"),
        Pair("Animes VOSTFR", "/animes-vostfr/"),
        Pair("Films VF et VOSTFR", "/films-vf-vostfr/"),
    )

    override val genres = arrayOf(
        Pair("<Sélectionner>", ""),
        Pair("Action", "/genre/action/"),
        Pair("Aventure", "/genre/aventure/"),
        Pair("Arts martiaux", "/genre/arts-martiaux/"),
        Pair("Combat", "/genre/combat/"),
        Pair("Comédie", "/genre/comedie/"),
        Pair("Drame", "/genre/drame/"),
        Pair("Epouvante", "/genre/epouvante/"),
        Pair("Fantastique", "/genre/fantastique/"),
        Pair("Fantasy", "/genre/fantasy/"),
        Pair("Mystère", "/genre/mystere/"),
        Pair("Romance", "/genre/romance/"),
        Pair("Shonen", "/genre/shonen/"),
        Pair("Surnaturel", "/genre/surnaturel/"),
        Pair("Sci-Fi", "/genre/sci-fi/"),
        Pair("School life", "/genre/school-life/"),
        Pair("Ninja", "/genre/ninja/"),
        Pair("Seinen", "/genre/seinen/"),
        Pair("Horreur", "/genre/horreur/"),
        Pair("Tranche de vie", "/genre/tranchedevie/"),
        Pair("Psychologique", "/genre/psychologique/"),
    )

    // ============================== Popular / Latest / Search ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-vostfr/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            fetch_type = FetchType.Episodes
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return super.searchAnimeFromElement(element).apply {
            fetch_type = FetchType.Episodes
        }
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return super.latestUpdatesFromElement(element).apply {
            fetch_type = FetchType.Episodes
        }
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        checkBaseUrl()
        return super.getAnimeDetails(anime).apply {
            fetch_type = FetchType.Episodes
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return super.animeDetailsParse(response).apply {
            fetch_type = FetchType.Episodes
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val epsData = document.selectFirst("div.eps, .eps, #eps, [id*=eps]")?.text()
        if (!epsData.isNullOrBlank()) {
            epsData.split(" ").filter { it.isNotBlank() }.forEach {
                val data = it.split("!", limit = 2)
                val episode = SEpisode.create()
                episode.episode_number = data[0].toFloatOrNull() ?: 0F
                episode.name = "Episode ${data[0]}"
                episode.url = data.getOrElse(1) { "" }
                if (episode.url.isNotBlank()) {
                    episodeList.add(episode)
                }
            }
        }

        // Fallback: If no div.eps was found, check iframes or player embeds (e.g. for movies)
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
                // Last fallback: add current page url so hosters can be extracted from page
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Épisode complet"
                        episode_number = 1F
                        url = response.request.url.encodedPath
                    }
                )
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================== Hosters ==============================

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
        UqloadExtractor(client),
        UpstreamExtractor(client),
        VoeExtractor(client, headers),
        SibnetExtractor(client),
        DoodWrapper(client),
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val urls = if (episode.url.startsWith("/")) {
            // It's a page path: fetch page and extract player iframe urls
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

        return urls.mapNotNull { url ->
            val extractor = extractorList.firstOrNull { it.supportsUrl(url) }
            if (extractor != null) extractor.hosterFromUrl(url)
            else DebugExtractor(BuildConfig.DEBUG).hosterFromUrl(url)
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

    companion object {
        private const val DEFAULT_DOMAIN = "https://french-anime.com"
    }
}
