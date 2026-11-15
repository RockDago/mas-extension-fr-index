package eu.kanade.tachiyomi.animeextension.fr.franime.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias Animes = List<Anime> // achyually it's "anime" not "animes": fuck you :3c

@Serializable
data class Anime(
    @SerialName("themes") val genres: List<String>,
    @SerialName("saisons") val seasons: List<Season>,
    @SerialName("_id") val uid: String?,
    @SerialName("id") val id: Long,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("banner") val banner: String = "",
    @SerialName("affiche") val poster: String = "",
    @SerialName("titleO") val originalTitle: String,
    @SerialName("title") val title: String,
    @SerialName("titles") val titlesAlt: TitlesAlt,
    @SerialName("description") val description: String,
    @SerialName("note") val note: Float,
    @SerialName("format") val format: String,
    @SerialName("startDate") val startDate: String?, // deserialize as date
    @SerialName("endDate") val endDate: String?, // ditto
    @SerialName("status") val status: String,
    @SerialName("nsfw") val nsfw: Boolean,
    @SerialName("__v") val uuv: Int?, // no idea wtf is this
    @SerialName("affiche_small") val posterSmall: String?,
    @SerialName("updatedDate") val updateTime: Long?, // deserialize as timestamp
)

@Serializable
data class Season(
    @SerialName("title") val title: String,
    @SerialName("episodes") val episodes: List<Episode>,
)

@Serializable
data class Episode(
    @SerialName("title") val title: String?,
    @SerialName("lang") val languages: EpisodeLanguages,
)

@Serializable
data class EpisodeLanguages(
    @SerialName("vf") val vf: EpisodeLanguage,
    @SerialName("vo") val vo: EpisodeLanguage,
)

@Serializable
data class EpisodeLanguage(
    @SerialName("lecteurs") val players: List<String>,
)

@Serializable
data class TitlesAlt(
    @SerialName("en") val en: String?,
    @SerialName("en_jp") val enJp: String?,
    @SerialName("ja_jp") val jaJp: String?,
)

@Serializable
data class AnimeSeasons(
    val kitsuId: Long?,
    val episodes: List<Episode> = emptyList()
) {
    @Serializable
    data class Episode(
        val number: Int,
        val title: String = "",
        val thumbnail: String = "",
        val airDate: String = "",
        @SerialName("_id") val uid: String = "",
    )
}