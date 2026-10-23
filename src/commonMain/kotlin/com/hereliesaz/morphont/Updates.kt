package com.hereliesaz.morphont

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where an update comes from: the channel this copy of Morphont was installed through. */
enum class UpdateSource(val label: String) { PLAY("Google Play"), GITHUB("GitHub") }

/** A newer Morphont than the one running, and where to get it. */
data class AvailableUpdate(val version: String, val source: UpdateSource, val url: String)

/**
 * What a platform gives the editor for updates: how to look for one on the channel this copy came
 * from, and how to send the user to it. [check] returns null when this is the newest; it throws when
 * it couldn't find out.
 */
class UpdateChecker(
    val check: suspend () -> AvailableUpdate?,
    val open: (AvailableUpdate) -> Unit,
)

/** Numeric parts of a version string ("v0.14.0.3" → [0, 14, 0, 3], "7" → [7]); null when it has none. */
fun versionParts(version: String): List<Int>? =
    Regex("""\d+(?:\.\d+)*""").find(version)?.value?.split('.')?.map { it.toIntOrNull() ?: return null }

/**
 * Whether [candidate] is newer than [current], comparing the first [parts] numbers (missing ones count
 * as 0).
 */
fun isNewerVersion(candidate: String, current: String, parts: Int = 4): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return false
    for (i in 0 until parts) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

const val GITHUB_REPOSITORY = "HereLiesAz/morphont"

/**
 * Looks for a newer Morphont among the repository's GitHub Releases. Only published, non-prerelease
 * releases with an asset this platform can install ([assetMatches]) count. A patch release collects
 * every build's installers (`0.15.0.1`, `0.15.0.5`, …), so each matching asset is read and the newest
 * wins; an asset name without a version falls back to the tag, then the title. Returns the newest
 * such build, if it is newer than [current].
 */
object GitHubReleases {
    private val json = Json { ignoreUnknownKeys = true }

    fun releasesUrl(repository: String = GITHUB_REPOSITORY) = "https://api.github.com/repos/$repository/releases?per_page=20"

    suspend fun latest(
        http: HttpGet,
        current: String,
        assetMatches: (String) -> Boolean,
        parts: Int = 4,
        repository: String = GITHUB_REPOSITORY,
    ): AvailableUpdate? = newest(http.get(releasesUrl(repository)).decodeToString(), current, assetMatches, parts)

    fun newest(text: String, current: String, assetMatches: (String) -> Boolean, parts: Int = 4): AvailableUpdate? {
        val releases = json.parseToJsonElement(text) as? JsonArray ?: return null
        var best: AvailableUpdate? = null
        for (r in releases) {
            val o = r as? JsonObject ?: continue
            fun s(k: String, from: JsonObject = o) = from[k]?.jsonPrimitive?.contentOrNull
            if (o["draft"]?.jsonPrimitive?.booleanOrNull == true || o["prerelease"]?.jsonPrimitive?.booleanOrNull == true) continue
            val url = s("html_url") ?: continue
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { a -> s("name", a) } }
                .filter(assetMatches)
            for (asset in assets) {
                val version = listOfNotNull(asset, s("tag_name"), s("name")).firstNotNullOfOrNull { candidate ->
                    versionParts(candidate)?.takeIf { it.size > 1 }?.joinToString(".")
                } ?: continue
                if (!isNewerVersion(version, current, parts)) continue
                if (best == null || isNewerVersion(version, best.version, parts)) best = AvailableUpdate(version, UpdateSource.GITHUB, url)
            }
        }
        return best
    }
}
