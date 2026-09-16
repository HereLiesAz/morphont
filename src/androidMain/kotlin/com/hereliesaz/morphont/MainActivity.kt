package com.hereliesaz.morphont

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val storage by lazy { AndroidStorage(this) }

    private var pendingJsonLoaded: ((String) -> Unit)? = null
    private var pendingJsonError: ((String) -> Unit)? = null
    private var pendingTtfLoaded: ((ByteArray) -> Unit)? = null
    private var pendingTtfError: ((String) -> Unit)? = null
    private var pendingSaveText: String? = null
    private var pendingSaveDone: (() -> Unit)? = null
    private var pendingSaveError: ((String) -> Unit)? = null

    private val openJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            clearJsonCallbacks()
            return@registerForActivityResult
        }
        try {
            val text = readText(uri)
            pendingJsonLoaded?.invoke(text)
        } catch (e: Exception) {
            pendingJsonError?.invoke("Couldn't read file: ${e.message}")
        } finally {
            clearJsonCallbacks()
        }
    }

    private val openTtfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            clearTtfCallbacks()
            return@registerForActivityResult
        }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("No readable stream returned")
            pendingTtfLoaded?.invoke(bytes)
        } catch (e: Exception) {
            pendingTtfError?.invoke("Couldn't read font: ${e.message}")
        } finally {
            clearTtfCallbacks()
        }
    }

    private val saveJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            clearSaveCallbacks()
            return@registerForActivityResult
        }
        try {
            val text = pendingSaveText ?: error("Nothing queued for export")
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(text) }
                ?: error("No writable stream returned")
            pendingSaveDone?.invoke()
        } catch (e: Exception) {
            pendingSaveError?.invoke("Couldn't save file: ${e.message}")
        } finally {
            clearSaveCallbacks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val files = AndroidFileActions(
            openJson = { onLoaded, onError ->
                pendingJsonLoaded = onLoaded
                pendingJsonError = onError
                openJsonLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
            },
            saveJson = { filename, text, onSaved, onError ->
                pendingSaveText = text
                pendingSaveDone = onSaved
                pendingSaveError = onError
                saveJsonLauncher.launch(filename)
            },
            openTtf = { onLoaded, onError ->
                pendingTtfLoaded = onLoaded
                pendingTtfError = onError
                openTtfLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream", "*/*"))
            },
        )

        setContent {
            AndroidApp(storage = storage, files = files)
        }
    }

    private fun readText(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("No readable stream returned")

    private fun clearJsonCallbacks() {
        pendingJsonLoaded = null
        pendingJsonError = null
    }

    private fun clearTtfCallbacks() {
        pendingTtfLoaded = null
        pendingTtfError = null
    }

    private fun clearSaveCallbacks() {
        pendingSaveText = null
        pendingSaveDone = null
        pendingSaveError = null
    }
}
