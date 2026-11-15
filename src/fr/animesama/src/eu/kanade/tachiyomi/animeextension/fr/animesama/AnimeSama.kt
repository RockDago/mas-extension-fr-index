package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.lib.smoothpreextractor.SmoothpreExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMap
import extensionsfr.utils.AnimeDomainSource
import extensionsfr.utils.Extractor
import extensionsfr.utils.LazyMutable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class AnimeSama : AnimeDomainSource() {

    override val name = "Anime-Sama"

    override val lang = "fr"

    override val supportsLatest = true

    override var baseUrl by LazyMutable {
        preferences.getString(PREF_DOMAIN_KEY, DEFAULT_DOMAIN)?.takeIf { it.isNotBlank() } ?: DEFAULT_DOMAIN
    }

    private val json: Json by injectLazy()

    // === Popular ===
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val anime = doc.select("#containerPepites .as-carte-oeuvre > a").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.attr("href").removeSuffix("/"))
                title = it.select("h3.as-carte-titre").text().ifBlank { it.attr("data-nom") }
                thumbnail_url = it.selectFirst("img")?.attr("src")
                fetch_type = FetchType.Episodes
            }
        }
        return AnimesPage(anime, false)
    }

    // === Latest ===
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("#containerAjoutsAnimes .as-carte-anime > a").map {
            val animeUrl = it.attr("href").removeSuffix("/")
                .substringBeforeLast("/").substringBeforeLast("/")
            SAnime.create().apply {
                setUrlWithoutDomain(animeUrl)
                title = it.select("h3.as-carte-titre").text().ifBlank { it.parent()?.attr("data-nom") ?: "" }
                thumbnail_url = it.selectFirst("img")?.attr("src")
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // === Search ===
    override fun getFilterList() = AnimeSamaFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogue/".toHttpUrl().newBuilder()
        val params = AnimeSamaFilters.getSearchFilters(filters)
        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }
        url.addQueryParameter("search", query)
        url.addQueryParameter("page", "$page")
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val anime = document.select("#list_catalog .as-carte-oeuvre > a").map {
            val href = it.attr("href").removeSuffix("/")
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = it.select("h3.as-carte-titre").text().ifBlank { it.parent()?.attr("data-nom") ?: "" }
                thumbnail_url = it.selectFirst("img")?.attr("src")
                background_url = thumbnail_url
                fetch_type = FetchType.Episodes
            }
        }
        val page = response.request.url.queryParameterValues("page").first()?.toInt() ?: 1
        val lastPage = document.select("#list_pagination a:last-child").getOrNull(0)?.text()?.toInt() ?: 1
        return AnimesPage(anime, page == lastPage)
    }

    // === Seasons ===
    override fun seasonListRequest(anime: SAnime) = GET("$baseUrl${anime.url}", headers)

    private val commentRegex by lazy { Regex("""/\*[\s\S]*?\*/|<!--[\s\S]*?-->|//.*""") }
    private val seasonRegex by lazy { Regex("""panneauAnime\(\s*["'](.*?)["']\s*,\s*["'](.*?)["']\s*\)""") }

    override fun seasonListParse(response: Response): List<SAnime> {
        val body = response.asJsoup()
        val anime = response.request.url.pathSegments.last { it.isNotEmpty() }
        val animeName = body.select("#titreOeuvre, .oe-titre, .oeuvre-right h1").first()?.text()
            ?: body.select("h1").first()?.text() ?: ""
        val thumbnailUrl = Regex("""OE_IMG\s*=\s*["']([^"']+)["']""").find(body.toString())?.groups?.get(1)?.value
            ?: body.select(".oe-affiche img, #coverOeuvre, .oeuvre-right img").first()?.attr("src")

        val scriptsText = body.select("script").joinToString("\n") { it.data().ifBlank { it.html() } }
        val cleanScripts = scriptsText.replace(commentRegex, "")
        val seasons = seasonRegex.findAll(cleanScripts)
            .filter { match ->
                val (name, stem) = match.destructured
                name != "nom" && stem != "url"
            }
            .map {
                val (seasonName, seasonStem) = it.destructured
                SAnime.create().apply {
                    url = "/catalogue/$anime/${seasonStem.removePrefix("/").removeSuffix("/")}"
                    title = "$animeName $seasonName"
                    thumbnail_url = thumbnailUrl
                    background_url = thumbnailUrl
                    description = body.select("#synopsisText, h2:contains(Synopsis) + .oe-synopsis p, h2:contains(Synopsis) + p").first()?.text() ?: ""
                    genre = body.select(".oe-genres .genre-pill, h2:contains(Genres) + a, h2:contains(Genres) + .oe-genres a").map { el -> el.text() }.joinToString(", ")
                    fetch_type = FetchType.Episodes
                    initialized = true
                }
            }.toList()

        return if (seasons.isNotEmpty()) {
            seasons
        } else {
            listOf(
                SAnime.create().apply {
                    url = "/catalogue/$anime"
                    title = animeName
                    thumbnail_url = thumbnailUrl
                    background_url = thumbnailUrl
                    description = body.select("#synopsisText, h2:contains(Synopsis) + .oe-synopsis p, h2:contains(Synopsis) + p").first()?.text() ?: ""
                    genre = body.select(".oe-genres .genre-pill, h2:contains(Genres) + a, h2:contains(Genres) + .oe-genres a").map { el -> el.text() }.joinToString(", ")
                    fetch_type = FetchType.Episodes
                    initialized = true
                }
            )
        }
    }

    // === Anime Details ===
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parts = anime.url.trim('/').split('/')
        val parentUrl = if (parts.size >= 2) {
            "/catalogue/${parts[1]}"
        } else {
            anime.url
        }
        return super.getAnimeDetails(SAnime.create().apply { url = parentUrl }).apply {
            title = anime.title.ifBlank { this.title }
            url = anime.url
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.asJsoup()
        return SAnime.create().apply {
            description = body.select("#synopsisText, h2:contains(Synopsis) + .oe-synopsis p, h2:contains(Synopsis) + p").first()?.text() ?: ""
            genre = body.select(".oe-genres .genre-pill, h2:contains(Genres) + a, h2:contains(Genres) + .oe-genres a").map { it.text() }.joinToString(", ")
            thumbnail_url = body.select(".oe-affiche img, #coverOeuvre, .oeuvre-right img").first()?.attr("src") ?: ""
            initialized = true
        }
    }

    // === Episodes ===
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val trimmed = anime.url.removePrefix("/").removeSuffix("/")
        val parts = trimmed.split('/')

        // If it's a parent anime page (/catalogue/<slug>, parts.size <= 2)
        if (parts.size <= 2) {
            val response = try {
                client.newCall(GET("$baseUrl/$trimmed", headers)).await()
            } catch (_: Exception) {
                null
            }

            if (response != null && response.isSuccessful) {
                val body = response.asJsoup()
                val scriptsText = body.select("script").joinToString("\n") { it.data().ifBlank { it.html() } }
                val cleanScripts = scriptsText.replace(commentRegex, "")
                val seasons = seasonRegex.findAll(cleanScripts)
                    .filter { match ->
                        val (name, stem) = match.destructured
                        name != "nom" && stem != "url"
                    }
                    .map {
                        val (name, stem) = it.destructured
                        Pair(name, stem.trim('/'))
                    }.toList()

                if (seasons.isNotEmpty()) {
                    val allEpisodes = mutableListOf<SEpisode>()
                    var globalEpIndex = 1
                    for ((seasonName, seasonStem) in seasons) {
                        val cleanStem = if (ALL_VOICE_TYPES.any { seasonStem.endsWith("/$it") }) {
                            seasonStem.substringBeforeLast('/')
                        } else {
                            seasonStem
                        }
                        val seasonSubPath = "/catalogue/${parts.last()}/$cleanStem"
                        val players = ALL_VOICE_TYPES.toList().parallelMap { voice ->
                            fetchPlayers("$baseUrl$seasonSubPath/$voice", "$seasonSubPath/$voice")
                        }
                        val seasonEpisodes = playersToEpisodes(
                            players,
                            seasonName = if (seasons.size > 1) seasonName else null,
                            startIndex = globalEpIndex,
                        )
                        allEpisodes.addAll(seasonEpisodes)
                        globalEpIndex += seasonEpisodes.size
                    }
                    return allEpisodes.reversed()
                }
            }
        }

        // Single season or specific season sub-path:
        val seasonPath = anime.url.removeSuffix("/")
        val seasonUrl = if (ALL_VOICE_TYPES.any { seasonPath.endsWith("/$it") }) {
            seasonPath.substringBeforeLast('/')
        } else {
            seasonPath
        }

        val players = ALL_VOICE_TYPES.toList().parallelMap { voice ->
            fetchPlayers("$baseUrl$seasonUrl/$voice", "$seasonUrl/$voice")
        }
        val episodes = playersToEpisodes(players)
        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not Used")

    // === Video Links ===
    override val extractorList: List<Extractor> = listOf(
        // SibnetExtractor(client),             // 400 everywhere
        // VkExtractor(client, headers),        // slow af
        // SendvidExtractor(client, headers),   // eternal maintenance
        VidMolyExtractor(client),
        SmoothpreExtractor(client),
        UqloadExtractor(client),
        VidHideExtractor(client, headers),
        VoeExtractor(client, headers),
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playerUrls = json.decodeFromString<List<List<String>>>(episode.url)
        val prefVoice = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT) ?: PREF_VOICES_DEFAULT

        val vfPlayers = playerUrls.getOrNull(0) ?: emptyList()
        val vostfrPlayers = playerUrls.getOrNull(1) ?: emptyList()

        val selectedVoicesWithPlayers: List<Pair<String, List<String>>> = when (prefVoice) {
            "vostfr" -> {
                if (vostfrPlayers.isNotEmpty()) {
                    listOf("vostfr" to vostfrPlayers, "vf" to vfPlayers)
                } else {
                    listOf("vf" to vfPlayers)
                }
            }
            "both_vf_first" -> {
                listOf("vf" to vfPlayers, "vostfr" to vostfrPlayers)
            }
            "vf_only" -> {
                listOf("vf" to vfPlayers)
            }
            else -> { // "vf" : Toujours FR / VF si disponible, sinon VOSTFR
                if (vfPlayers.isNotEmpty()) {
                    listOf("vf" to vfPlayers)
                } else {
                    listOf("vostfr" to vostfrPlayers)
                }
            }
        }

        val hosters = selectedVoicesWithPlayers.flatMap { (voice, playerList) ->
            val prefix = "(${voice.uppercase()}) "
            playerList.parallelMap { player ->
                try {
                    resolvePlayer(player, prefix)
                } catch (_: Throwable) {
                    null
                }
            }.filterNotNull()
        }

        if (hosters.isEmpty() && prefVoice == "vf" && vostfrPlayers.isNotEmpty()) {
            return vostfrPlayers.parallelMap { player ->
                try {
                    resolvePlayer(player, "(VOSTFR) ")
                } catch (_: Throwable) {
                    null
                }
            }.filterNotNull()
        }

        return hosters.also { Log.d(name, it.joinToString { "${it.hosterName} (${it.hosterUrl})" }) }
    }

    override fun hosterListParse(response: Response) = throw UnsupportedOperationException()

    private fun playersToEpisodes(
        list: List<List<List<String>>>,
        seasonName: String? = null,
        startIndex: Int = 1,
    ): List<SEpisode> =
        List(list.fold(0) { acc, it -> maxOf(acc, it.size) }) { i ->
            val episodeNumber = i + 1
            val players = list.map { it.getOrElse(i) { emptyList() } }
            val hasVf = players.getOrNull(0)?.isNotEmpty() == true
            val hasVostfr = players.getOrNull(1)?.isNotEmpty() == true

            SEpisode.create().apply {
                name = if (seasonName != null) {
                    "$seasonName Épisode $episodeNumber"
                } else {
                    "Épisode $episodeNumber"
                }
                url = json.encodeToString(players)
                episode_number = (startIndex + i).toFloat()
                scanlator = when {
                    hasVf -> "VF"
                    hasVostfr -> "VOSTFR"
                    else -> ""
                }
            }
        }

    private suspend fun fetchPlayers(pageUrl: String, seasonSubPath: String): List<List<String>> {
        val targetUrl = if (pageUrl.endsWith("/")) pageUrl else "$pageUrl/"
        val res = try {
            client.newCall(GET(targetUrl, headers)).await()
        } catch (_: Exception) {
            return emptyList()
        }
        if (!res.isSuccessful) return emptyList()

        val body = res.body.string()
        val script = Jsoup.parse(body).select("script").map { it.data() }.firstOrNull { it.contains("eps1") }
            ?: Regex("""(?s)<script[^>]*>([\s\S]*?var\s+eps1\s*=[\s\S]*?)</script>""").find(body)?.groups?.get(1)?.value

        val scriptContent = if (script != null) {
            script
        } else {
            try {
                val jsRes = client.newCall(GET("${targetUrl.removeSuffix("/")}/episodes.js", headers)).await()
                if (jsRes.isSuccessful) jsRes.body.string() else return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
        }

        return parsePlayerArrays(scriptContent, seasonSubPath)
    }

    private fun parsePlayerArrays(script: String, seasonSubPath: String): List<List<String>> {
        try {
            val evalCode = """
                $script;
                (function() {
                    var jeton = typeof epsJeton !== 'undefined' ? epsJeton : '';
                    var meta = typeof epsMeta !== 'undefined' ? epsMeta : {};
                    var players = [];
                    for (var i = 1; i <= 20; i++) {
                        try {
                            var arr = eval('typeof eps' + i + ' !== "undefined" ? eps' + i + ' : null');
                            if (arr && Array.isArray(arr)) {
                                players.push(arr);
                            }
                        } catch (e) {}
                    }
                    return JSON.stringify({ jeton: jeton, meta: meta, players: players });
                })()
            """.trimIndent()

            val jsonStr = try {
                QuickJs.create().use { qjs ->
                    qjs.evaluate(evalCode) as? String
                }
            } catch (_: Throwable) {
                null
            }

            if (!jsonStr.isNullOrBlank()) {
                val jsonRoot = json.parseToJsonElement(jsonStr).jsonObject
                val jeton = jsonRoot["jeton"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val metaObj = jsonRoot["meta"]?.jsonObject
                val meta = metaObj?.mapValues { entry ->
                    entry.value.toString().replace("\"", "").replace("[", "").replace("]", "").split(",").firstOrNull().orEmpty().trim()
                }.orEmpty()

                val playersArray = jsonRoot["players"]?.toString()
                if (!playersArray.isNullOrBlank()) {
                    val rawPlayers: List<List<String>> = json.decodeFromString(playersArray)
                    if (rawPlayers.isNotEmpty() && rawPlayers[0].isNotEmpty()) {
                        val numEpisodes = rawPlayers[0].size
                        return List(numEpisodes) { epIndex ->
                            rawPlayers.mapNotNull { it.getOrNull(epIndex) }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .map { p ->
                                    if (p.startsWith("lien:")) {
                                        val lienId = p.removePrefix("lien:")
                                        val domain = meta[lienId].orEmpty()
                                        "$p|$jeton|$domain|$seasonSubPath"
                                    } else {
                                        p
                                    }
                                }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.d(name, "QuickJs parse failed: ${e.message}")
        }

        // Fallback: Regex extraction
        val jeton = Regex("""var\s+epsJeton\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1).orEmpty()
        val metaJson = Regex("""var\s+epsMeta\s*=\s*(\{[\s\S]*?\});""").find(script)?.groupValues?.get(1)
        val meta: Map<String, String> = try {
            if (metaJson != null) {
                json.parseToJsonElement(metaJson).jsonObject.mapValues { entry ->
                    entry.value.toString().replace("\"", "").replace("[", "").replace("]", "").split(",").firstOrNull().orEmpty().trim()
                }
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val epsMatches = Regex("""var\s+eps\d+\s*=\s*(\[[^\]]*\]);""").findAll(script)
        val rawPlayers = epsMatches.mapNotNull { match ->
            try {
                json.decodeFromString<List<String>>(match.groupValues[1])
            } catch (_: Exception) {
                null
            }
        }.toList()

        if (rawPlayers.isEmpty() || rawPlayers[0].isEmpty()) return emptyList()

        val numEpisodes = rawPlayers[0].size
        return List(numEpisodes) { epIndex ->
            rawPlayers.mapNotNull { it.getOrNull(epIndex) }
                .filter { it.isNotBlank() }
                .distinct()
                .map { p ->
                    if (p.startsWith("lien:")) {
                        val lienId = p.removePrefix("lien:")
                        val domain = meta[lienId].orEmpty()
                        "$p|$jeton|$domain|$seasonSubPath"
                    } else {
                        p
                    }
                }
        }
    }

    private suspend fun resolvePlayer(player: String, prefix: String): Hoster? {
        val rawUrl: String = if (player.startsWith("lien:")) {
            val parts = player.split("|")
            val lienId = parts[0].removePrefix("lien:")
            val jeton = parts.getOrNull(1).orEmpty()
            val domain = parts.getOrNull(2).orEmpty()
            val seasonSubPath = parts.getOrNull(3).orEmpty()

            if (domain.isNotBlank() && extractorList.none { it.supportsUrl(domain) }) {
                return null
            }

            resolveLien(lienId, jeton, seasonSubPath) ?: return null
        } else {
            player
        }

        val extractor = extractorList.firstOrNull { it.supportsUrl(rawUrl) } ?: return null
        return extractor.hosterFromUrl(rawUrl, { "$prefix$it" })
    }

    private suspend fun resolveLien(lienId: String, jeton: String, seasonSubPath: String): String? {
        if (lienId.isBlank()) return null
        val url = "$baseUrl/api/lien.php?j=$jeton&l=$lienId"
        val reqHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val res = try {
            client.newCall(GET(url, reqHeaders)).await()
        } catch (_: Exception) {
            return null
        }
        if (!res.isSuccessful) return null
        val bodyStr = res.body.string()
        val jsonElement = try {
            json.parseToJsonElement(bodyStr)
        } catch (_: Exception) {
            return null
        }

        val u = jsonElement.jsonObject["u"]?.jsonPrimitive?.contentOrNull
        if (!u.isNullOrBlank()) {
            return u
        }

        val err = jsonElement.jsonObject["erreur"]?.jsonPrimitive?.contentOrNull
        if (err == "expire" && seasonSubPath.isNotBlank()) {
            val freshJeton = fetchFreshJeton("$baseUrl$seasonSubPath")
            if (!freshJeton.isNullOrBlank() && freshJeton != jeton) {
                return resolveLien(lienId, freshJeton, "")
            }
        }
        return null
    }

    private suspend fun fetchFreshJeton(pageUrl: String): String? {
        val targetUrl = if (pageUrl.endsWith("/")) pageUrl else "$pageUrl/"
        val res = try {
            client.newCall(GET(targetUrl, headers)).await()
        } catch (_: Exception) {
            return null
        }
        if (!res.isSuccessful) return null
        val body = res.body.string()
        return Regex("""var\s+epsJeton\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualité préférée (certains lecteurs uniquement)"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_VOICES_KEY
            title = "Préférence des voix"
            entries = VOICES
            entryValues = VOICES_VALUES
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
        private const val DEFAULT_DOMAIN = "https://anime-sama.to"

        private val VOICES = arrayOf(
            "Toujours VF si disponible (sinon VOSTFR)",
            "VF et VOSTFR (VF en premier)",
            "VOSTFR uniquement",
            "VF uniquement",
        )

        private val VOICES_VALUES = arrayOf(
            "vf",
            "both_vf_first",
            "vostfr",
            "vf_only",
        )

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vf"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val ALL_VOICE_TYPES = arrayOf("vf", "vostfr")
    }
}
