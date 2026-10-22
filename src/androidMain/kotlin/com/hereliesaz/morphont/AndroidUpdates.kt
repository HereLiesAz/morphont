package com.hereliesaz.morphont

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo

/**
 * Android updates come from wherever this copy was installed: a copy Google Play installed asks Play
 * (the in-app update API) and opens its Play listing; any other copy -- the GitHub APK, sideloaded --
 * looks for a newer `.apk` among the GitHub Releases and opens that release.
 */
class AndroidUpdates(context: Context) {
    private val context = context.applicationContext
    private val pkg = this.context.packageName

    private fun installer(): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    private fun versionName(): String {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        return info.versionName ?: "0"
    }

    private suspend fun fromPlay(): AvailableUpdate? {
        val info = AppUpdateManagerFactory.create(context).requestAppUpdateInfo()
        return if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            AvailableUpdate("", UpdateSource.PLAY, "market://details?id=$pkg")
        } else null
    }

    private fun view(url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    val checker = UpdateChecker(
        check = {
            if (installer() == PLAY_STORE) fromPlay()
            else GitHubReleases.latest(AndroidFontStore.http, versionName(), { it.endsWith(".apk", ignoreCase = true) })
        },
        open = { update ->
            try {
                view(update.url)
            } catch (_: ActivityNotFoundException) {
                // No Play Store app to take market:// links: its web page does the same job.
                if (update.source == UpdateSource.PLAY) runCatching { view("https://play.google.com/store/apps/details?id=$pkg") }
            }
        },
    )

    private companion object {
        const val PLAY_STORE = "com.android.vending"
    }
}
