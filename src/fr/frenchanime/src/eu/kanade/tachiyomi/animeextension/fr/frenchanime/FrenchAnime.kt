package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.datalifeengine.DataLifeEngine
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensionsfr.utils.DebugExtractor
import extensionsfr.utils.Extractor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class FrenchAnime : DataLifeEngine(
    "French Anime",
    "fr",
) {

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

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-vostfr/page/$page/")

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()
        epsData.split(" ").filter { it.isNotBlank() }.forEach {
            val data = it.split("!", limit = 2)
            val episode = SEpisode.create()
            episode.episode_number = data[0].toFloatOrNull() ?: 0F
            episode.name = "Episode ${data[0]}"
            episode.url = data[1]
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================== Hosters ==============================

    override val extractorList: List<Extractor> = listOf(
        VidMolyExtractor(client),
        UqloadExtractor(client),
        UpstreamExtractor(client),
        VoeExtractor(client, headers),
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return episode.url.split(",").filter { it.isNotBlank() }.mapNotNull { url ->
            val extractor = extractorList.firstOrNull { it.supportsUrl(url) }
            if (extractor != null) extractor.hosterFromUrl(url)
            else DebugExtractor(BuildConfig.DEBUG).hosterFromUrl(url)
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
}
