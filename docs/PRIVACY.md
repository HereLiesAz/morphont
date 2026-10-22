# Morphont Privacy Policy

_Effective 22 October 2026. Applies to Morphont on Android, Windows, macOS, Linux and the web (PWA)._

Morphont is a glyph editor. It collects nothing.

## What Morphont stores

Everything you make — glyphs, their anchors, guides, ghosts and metrics — is stored **only on your
device**:

- **Android:** the app's private storage.
- **Desktop:** your user app-data folder (`%APPDATA%\Morphont` on Windows,
  `~/Library/Application Support/Morphont` on macOS, `~/.local/share/morphont` on Linux).
- **Web:** your browser's IndexedDB for this site.

Fonts you install from the font store are kept in the same place (a `fonts` folder, or a separate
IndexedDB database on the web) until you remove them.

Uninstalling the app (or clearing the site's data in your browser) deletes it.

## What Morphont sends

Nothing about you or your work. Morphont has no accounts, no analytics, no advertising, no crash
reporting and no telemetry. The web version is served as static files by GitHub Pages; after it
loads, it works offline. GitHub's own handling of page requests is covered by
[GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

**Update check.** When it starts, and when you choose Check for updates, the Android and desktop
apps ask where they were installed from whether a newer Morphont exists: a copy from Google Play asks
Google Play; any other copy reads the public list of Morphont releases on
[GitHub](https://github.com/HereLiesAz/morphont/releases). The request carries nothing from your
projects; GitHub's and Google's handling of it is covered by their own privacy statements.

The other network feature is the **font store**, and it runs only when you open it. Browsing and
installing fetch the catalog and the font packages you choose from
[azphalt.store](https://azphalt.store) — ordinary web requests carrying your search text and the
package you asked for, and nothing from your projects. These two are why the Android app asks for
internet access. The store's own handling of requests is covered by its operator's terms.

## Files you open or save

When you import a font or project, or export a glyph or project, Morphont reads or writes only the
file you pick in your system's file dialog. Fonts you load as reference ghosts stay in memory for
that session and are not stored or transmitted. "Install on this computer" copies an installed store
font into your user font folder; on Android and the web, "Save font file" and "Download font file"
write it where you choose.

## Children

Morphont collects no personal information from anyone, including children.

## Changes

Any change to this policy is published here, with a new effective date, and in the repository's
history.

## Contact

Questions: open an issue at <https://github.com/HereLiesAz/morphont/issues>.
