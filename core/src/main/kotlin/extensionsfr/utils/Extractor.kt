package extensionsfr.utils

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers

abstract class Extractor {

    abstract val name: String

    abstract val supportedUrls: List<String>

    open fun supportsUrl(url: String): Boolean = supportedUrls.any { url.contains(it, true) }

    open fun hosterFromUrl(
        url: String,
        hosterNameGen: (String) -> String = { hosterName -> hosterName },
        lazy: Boolean = false,
        internalData: String = "",
    ): Hoster =
        Hoster(
            hosterUrl = url,
            hosterName = hosterNameGen(name),
            internalData = internalData,
            lazy = lazy,
        )

    abstract fun videosFromHoster(hoster: Hoster): List<Video>

    open fun videosFromUrl(url: String, headers: Headers = Headers.EMPTY) = videosFromHoster(hosterFromUrl(url))

    open fun resolveVideo(video: Video): Video?
        = throw RuntimeException("$name called resolveVideo! ${video.videoUrl}\n${video.internalData}")
}