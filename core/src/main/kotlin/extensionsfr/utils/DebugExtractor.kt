package extensionsfr.utils

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video

class DebugExtractor(private val enabled: Boolean = false): Extractor() {

    override val name = "DebugExtractor"

    override val supportedUrls: List<String> = emptyList()

    override fun hosterFromUrl(
        url: String,
        hosterNameGen: (String) -> String,
        lazy: Boolean,
        internalData: String
    ): Hoster {
        Log.d(name, url)
        return super.hosterFromUrl(url, { n -> "$n $url" }, enabled, internalData)
    }

    override fun videosFromHoster(hoster: Hoster): List<Video> = emptyList()
}