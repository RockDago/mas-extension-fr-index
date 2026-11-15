package eu.kanade.tachiyomi.animeextension.fr.franime.dto

import kotlinx.serialization.Serializable

@Serializable
data class Images(
    val data: Data
) {
    @Serializable
    data class Data(
        val attributes: Attributes
    ) {

        @Serializable
        data class Attributes(
            val posterImage: Image,
            val coverImage: Image,
        ) {

            @Serializable
            data class Image(
                val tiny: String,
                val large: String,
                val small: String,
                val original: String,
            )
        }
    }
}