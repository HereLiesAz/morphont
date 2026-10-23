package com.hereliesaz.morphont

import java.awt.Desktop
import java.net.URI

/**
 * Desktop updates come from GitHub Releases: the newest release carrying this system's installer
 * (`.msi`, `.dmg`, or `.deb`/`.rpm`) newer than [DESKTOP_VERSION], opened in the browser. Installers
 * are named with the full four-part version (`Morphont-0.15.0.1-windows-x64.msi`) and the app is
 * built with that same number, so all four parts compare. (The `.dmg`'s internal bundle version is
 * 1.x because macOS refuses 0.x; the file name, which is what's read here, is not.)
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
        check = { GitHubReleases.latest(DesktopFontStore.http, DESKTOP_VERSION, installerFor()) },
        open = { browse(it.url) },
    )
}
