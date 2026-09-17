package com.hereliesaz.morphont

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        try {
            Storage.initialize()
        } catch (e: Throwable) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "storage error"
            window.alert(
                "Morphont could not open browser storage: $detail. " +
                    "The editor will still open, but use Save project for a portable backup.",
            )
        }

        ComposeViewport(document.body!!) {
            App()
        }
    }
}
