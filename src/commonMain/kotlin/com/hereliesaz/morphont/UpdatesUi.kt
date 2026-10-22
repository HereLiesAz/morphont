package com.hereliesaz.morphont

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

private sealed interface UpdateResult {
    data object Checking : UpdateResult
    data object Current : UpdateResult
    data class Found(val update: AvailableUpdate) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * Looks for an update once, when the app starts, and says so only if there is one. Silent when
 * there isn't, or when the check can't reach its source (offline is normal for an editor).
 */
@Composable
fun AutoUpdatePrompt(checker: UpdateChecker?) {
    checker ?: return
    var found by remember { mutableStateOf<AvailableUpdate?>(null) }
    LaunchedEffect(checker) {
        found = try { checker.check() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    found?.let { UpdateFoundDialog(it, checker) { found = null } }
}

/** "Check for updates…": always answers -- newer version, up to date, or why it couldn't tell. */
@Composable
fun UpdateCheckDialog(checker: UpdateChecker, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<UpdateResult>(UpdateResult.Checking) }
    LaunchedEffect(checker) {
        result = try {
            checker.check()?.let { UpdateResult.Found(it) } ?: UpdateResult.Current
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateResult.Failed(e.message ?: "Couldn't check for updates.")
        }
    }
    when (val r = result) {
        is UpdateResult.Found -> UpdateFoundDialog(r.update, checker, onDismiss)
        else -> MonoDialog("Updates", onDismiss) {
            Text(
                when (r) {
                    UpdateResult.Checking -> "Checking…"
                    UpdateResult.Current -> "This is the newest Morphont."
                    is UpdateResult.Failed -> r.message
                    is UpdateResult.Found -> ""
                },
                fontSize = 14.sp, color = if (r is UpdateResult.Failed) Mono.error else Mono.ink,
            )
        }
    }
}

@Composable
private fun UpdateFoundDialog(update: AvailableUpdate, checker: UpdateChecker, onDismiss: () -> Unit) {
    MonoDialog("Update available", onDismiss, actions = {
        MonoButton(onClick = onDismiss, outlined = true) { Text("Later", fontSize = 13.sp) }
        MonoButton(onClick = { checker.open(update); onDismiss() }, selected = true) {
            Text("Open ${update.source.label}", fontSize = 13.sp)
        }
    }) {
        Text(
            if (update.version.isEmpty()) "A newer Morphont is available on ${update.source.label}."
            else "Morphont ${update.version} is available on ${update.source.label}.",
            fontSize = 14.sp, color = Mono.ink,
        )
    }
}
