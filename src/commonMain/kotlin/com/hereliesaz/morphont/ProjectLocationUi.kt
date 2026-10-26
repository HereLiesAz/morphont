package com.hereliesaz.morphont

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What a shell that can keep projects in a folder of the person's choosing does about it. */
class ProjectLocationHost(
    /** Asks for a folder, then moves the open project into it and makes it the home of the projects started after. */
    val chooseFolder: () -> Unit,
    /** Brings the open project back into the app's own storage; the folder keeps its copy. */
    val useAppStorage: () -> Unit,
    /** Goes back to making new projects in the app's own storage. */
    val newProjectsInAppStorage: () -> Unit,
)

/** The open project's home, with a way to change it. [onClose] closes whatever holds this row, once the picker is on its way. */
@Composable
fun ProjectLocationRow(app: AppState, host: ProjectLocationHost, onClose: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val project = app.currentProject ?: return
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Caps("Kept in")
            Text(project.locationLabel, fontSize = Type.Body, color = Mono.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        MonoButton(onClick = { open = true }, outlined = true) { Text("Change") }
    }
    if (open) MonoDialog("Where \"${project.name}\" is kept", onDismiss = { open = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "In a folder you choose, a project is ordinary files: you can see them, back them up, sync them, and they stay when the app is removed. " +
                    "In app storage they're private to Morphont.",
                fontSize = Type.Body, color = Mono.inkDim,
            )
            Caps("Now")
            Text(project.locationLabel, fontSize = Type.Row, color = Mono.ink)
            MonoButton(onClick = { open = false; onClose(); host.chooseFolder() }, selected = true, modifier = Modifier.fillMaxWidth()) {
                Text(if (project.location == null) "Move to a folder…" else "Move to another folder…")
            }
            if (project.location != null) MonoButton(onClick = { open = false; onClose(); host.useAppStorage() }, outlined = true, modifier = Modifier.fillMaxWidth()) {
                Text("Move back to app storage")
            }
            app.newProjectsFolder?.let { home ->
                Caps("New projects", modifier = Modifier.padding(top = 4.dp))
                Text("Started in ${home.label}.", fontSize = Type.Body, color = Mono.ink)
                MonoButton(onClick = { host.newProjectsInAppStorage(); open = false }, outlined = true, modifier = Modifier.fillMaxWidth()) {
                    Text("Start new projects in app storage")
                }
            }
        }
    }
}
