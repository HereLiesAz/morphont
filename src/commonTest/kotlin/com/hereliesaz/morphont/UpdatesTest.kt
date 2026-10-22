package com.hereliesaz.morphont

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("0.14.0.10", "0.14.0.9"))
        assertTrue(isNewerVersion("v0.15.0", "0.14.9.99"))
        assertFalse(isNewerVersion("0.14.0", "0.14.0.0"))
        assertFalse(isNewerVersion("0.13.9", "0.14.0"))
        // Desktop: the run number on an installer is not a newer version.
        assertFalse(isNewerVersion("0.14.0.7", "0.14.0", parts = 3))
        assertTrue(isNewerVersion("0.14.1.1", "0.14.0", parts = 3))
        assertFalse(isNewerVersion("latest", "0.14.0"))
    }

    private val releases = """[
      {"tag_name":"latest","name":"morphont (Latest)","html_url":"https://github.com/HereLiesAz/morphont/releases/tag/latest","draft":false,"prerelease":false,
       "assets":[{"name":"Morphont-0.16.0.4.msi"},{"name":"Morphont-0.16.0.4.dmg"}]},
      {"tag_name":"v0.15.0","name":"morphont 0.15.0","html_url":"https://github.com/HereLiesAz/morphont/releases/tag/v0.15.0","draft":false,"prerelease":false,
       "assets":[{"name":"Morphont-0.15.0.3.msi"},{"name":"morphont-0.15.0.3.deb"}]},
      {"tag_name":"0.15.2.1","name":"morphont 0.15.2.1","html_url":"https://github.com/HereLiesAz/morphont/releases/tag/0.15.2.1","draft":false,"prerelease":false,
       "assets":[{"name":"morphont-v0.15.2.1.apk"}]},
      {"tag_name":"0.17.0.1","html_url":"https://github.com/HereLiesAz/morphont/releases/tag/0.17.0.1","draft":false,"prerelease":true,
       "assets":[{"name":"morphont-v0.17.0.1.apk"}]},
      {"tag_name":"0.18.0.1","html_url":"x","draft":true,"prerelease":false,"assets":[{"name":"morphont-v0.18.0.1.apk"}]}
    ]"""

    @Test
    fun picksTheNewestReleaseWithThisPlatformsInstaller() {
        val msi = GitHubReleases.newest(releases, "0.14.0", { it.endsWith(".msi") }, parts = 3)
        assertEquals(AvailableUpdate("0.16.0.4", UpdateSource.GITHUB, "https://github.com/HereLiesAz/morphont/releases/tag/latest"), msi)
        val deb = GitHubReleases.newest(releases, "0.14.0", { it.endsWith(".deb") }, parts = 3)
        assertEquals("0.15.0.3", deb?.version)
        // Prereleases and drafts don't count.
        val apk = GitHubReleases.newest(releases, "0.14.0.1", { it.endsWith(".apk") })
        assertEquals("0.15.2.1", apk?.version)
        assertNull(GitHubReleases.newest(releases, "0.15.2.1", { it.endsWith(".apk") }))
        assertNull(GitHubReleases.newest(releases, "0.16.0", { it.endsWith(".msi") }, parts = 3))
        assertNull(GitHubReleases.newest("{}", "0.1.0", { true }))
    }
}
