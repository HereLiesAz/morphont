package com.hereliesaz.morphont

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks where to create a file, like [ActivityResultContracts.CreateDocument] but with the MIME type chosen per
 * request: the input is the suggested file name and its MIME type. Providers rename a file whose extension
 * doesn't match the type they were asked for, so the type has to follow the extension.
 */
private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Asks for a folder, with the grants that let the app keep reading and writing it after the app restarts. */
private class PickFolder : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * The manifest keeps this activity across rotation, resizing and theme changes (Compose re-lays itself
 * out), so a file picker's pending callbacks and the editor's state survive them. Only process death
 * can still lose a pending callback; [lostRequest] says so instead of failing silently.
 */
class MainActivity : ComponentActivity() {
    private val storage by lazy { AndroidStorage(this) }
    private val updates by lazy { AndroidUpdates(this) }

    private var pendingJsonLoaded: ((String) -> Unit)? = null
    private var pendingJsonError: ((String) -> Unit)? = null
    private var pendingTtfLoaded: ((ByteArray) -> Unit)? = null
    private var pendingTtfError: ((String) -> Unit)? = null
    private var pendingSaveText: String? = null
    private var pendingSaveDone: (() -> Unit)? = null
    private var pendingSaveError: ((String) -> Unit)? = null
    private var pendingFontBytes: ByteArray? = null
    private var pendingFontDone: (() -> Unit)? = null
    private var pendingFontError: ((String) -> Unit)? = null
    private var pendingFolderPicked: ((Uri) -> Unit)? = null
    private var pendingFolderError: ((String) -> Unit)? = null

    /** A picker answered after the app was restarted, so what asked for it is gone. */
    private fun lostRequest(what: String, created: Uri? = null) {
        // A CreateDocument has already made an empty file; don't leave it looking like a backup.
        created?.let { runCatching { DocumentsContract.deleteDocument(contentResolver, it) } }
        Toast.makeText(this, "Morphont restarted while the file picker was open. $what again.", Toast.LENGTH_LONG).show()
    }

    private val openJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            clearJsonCallbacks()
            return@registerForActivityResult
        }
        if (pendingJsonLoaded == null) { lostRequest("Open it"); return@registerForActivityResult }
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
        if (pendingTtfLoaded == null) { lostRequest("Choose the font"); return@registerForActivityResult }
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
        if (pendingSaveText == null) { lostRequest("Export it", created = uri); return@registerForActivityResult }
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

    private val saveFontLauncher = registerForActivityResult(CreateFile()) { uri ->
        val done = pendingFontDone
        val fail = pendingFontError
        val bytes = pendingFontBytes
        pendingFontBytes = null
        pendingFontDone = null
        pendingFontError = null
        if (uri == null) {
            fail?.invoke("Not saved.")
            return@registerForActivityResult
        }
        if (bytes == null) { lostRequest("Save the font", created = uri); return@registerForActivityResult }
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("No writable stream returned")
            done?.invoke()
        } catch (e: Exception) {
            fail?.invoke("Couldn't save the font: ${e.message}")
        }
    }

    private val pickFolderLauncher = registerForActivityResult(PickFolder()) { uri ->
        val picked = pendingFolderPicked
        val fail = pendingFolderError
        pendingFolderPicked = null
        pendingFolderError = null
        if (uri == null) return@registerForActivityResult // they backed out
        if (picked == null) { lostRequest("Choose the folder"); return@registerForActivityResult }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            fail?.invoke("Couldn't keep access to that folder: ${e.message}")
            return@registerForActivityResult
        }
        picked(uri)
    }

    override fun onStop() {
        // Backgrounded or closing: write the edit still waiting on the autosave debounce.
        AndroidAutosave.flush()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both themes are dark, so the system bars always carry light icons, whatever the phone's own theme.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))

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
            saveFont = { filename, bytes, onSaved, onError -> saveFile(filename, "font/ttf", bytes, onSaved, onError) },
            saveFile = { filename, mimeType, bytes, onSaved, onError -> saveFile(filename, mimeType, bytes, onSaved, onError) },
            pickFolder = { onPicked, onError ->
                pendingFolderPicked = onPicked
                pendingFolderError = onError
                pickFolderLauncher.launch(Unit)
            },
        )

        setContent {
            AndroidApp(storage = storage, files = files, updates = updates.checker)
        }
    }

    private fun saveFile(filename: String, mimeType: String, bytes: ByteArray, onSaved: () -> Unit, onError: (String) -> Unit) {
        // A save still waiting on the picker loses to the newer one, and says so.
        pendingFontError?.invoke("Not saved.")
        pendingFontBytes = bytes
        pendingFontDone = onSaved
        pendingFontError = onError
        saveFontLauncher.launch(filename to mimeType)
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
