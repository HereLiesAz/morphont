package com.hereliesaz.morphont

import java.awt.Desktop
import java.net.URI

/**
 * Desktop updates come from GitHub Releases: the newest release carrying this system's installer
 * (`.msi`, `.dmg`, or `.deb`/`.rpm`) newer than [DESKTOP_VERSION], opened in the browser.
 */
object DesktopUpdates {
    internal fun installerFor(os: String = System.getProperty("os.name").lowercase()): (String) -> Boolean = { name ->
        val n = name.lowercase()
        when {
            os.contains("win") -> n.endsWith(".msi")
            os.contains("mac") -> n.endsWith(".dmg")
            else -> n.endsWith(".deb") || n.endsWith(".rpm")
        }
    }

    private fun browse(url: String) {
        val ok = runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
                Desktop.getDesktop().browse(URI(url)).let { true }
        }.getOrDefault(false)
        if (!ok) runCatching { ProcessBuilder("xdg-open", url).start() }
    }

    val checker = UpdateChecker(
        // Installers carry a run number the app doesn't; major.minor.patch decides.
        check = { GitHubReleases.latest(DesktopFontStore.http, DESKTOP_VERSION, installerFor(), parts = 3) },
        open = { browse(it.url) },
    )
}
