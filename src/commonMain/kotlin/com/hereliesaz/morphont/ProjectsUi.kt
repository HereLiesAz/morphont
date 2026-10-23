package com.hereliesaz.morphont

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The saved projects, one per row: tap a name to open it, or rename / delete
 * it. Deleting asks first. [onSwitched] runs after a project has been opened.
 */
@Composable
private fun ProjectRows(app: AppState, actions: ProjectActions, onSwitched: () -> Unit) {
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    Column {
        for (project in app.projects) {
            val current = project.id == app.currentProject?.id
            when {
                renaming == project.id -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoField(renameText, { renameText = it }, "Project name", Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoButton(onClick = { renaming = null }, outlined = true) { Text("Cancel") }
                        MonoButton(onClick = { if (actions.rename(project.id, renameText)) renaming = null }, selected = true, enabled = renameText.isNotBlank()) { Text("Rename") }
                    }
                }
                deleting == project.id -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Delete \"${project.name}\" and all its glyphs? This can't be undone -- open it and save the whole project first if you might want it back.", fontSize = 14.sp, color = Mono.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MonoButton(onClick = { deleting = null }, outlined = true) { Text("Cancel") }
                        MonoButton(onClick = { deleting = null; actions.delete(project.id) }, selected = true) { Text("Delete") }
                    }
                }
                else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open") { actions.open(project.id); onSwitched() }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    ) {
                        Text(project.name, fontSize = 17.sp, color = Mono.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (current) Caps("Open now", size = 10, color = Mono.ink)
                    }
                    MonoButton(onClick = { renameText = project.name; renaming = project.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
                    MonoButton(onClick = { deleting = project.id }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Mono.border))
        }
    }
}

@Composable
private fun NewProjectRow(actions: ProjectActions, onSwitched: () -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val create = { if (actions.create(name)) { name = ""; onSwitched() } }
        MonoField(name, { name = it }, "New project name", Modifier.weight(1f), onDone = { if (name.isNotBlank()) create() })
        MonoButton(onClick = create, selected = true, enabled = name.isNotBlank()) { Text("Create") }
    }
}

/**
 * What's shown until a project is open: the shared head, the ways in that make a project of their own
 * ([starts]: import a font, open a project file), a new empty project, or a saved one.
 */
@Composable
fun ProjectsScreen(app: AppState, actions: ProjectActions, modifier: Modifier = Modifier, starts: List<Pair<String, () -> Unit>> = emptyList()) {
    Box(modifier.fillMaxSize().background(Mono.ground).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        val scroll = rememberScrollState()
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(scroll).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            MorphontHero()
            if (starts.isNotEmpty()) StartList(starts)
            Caps(if (app.projects.isEmpty()) "Or start an empty project" else "New project")
            NewProjectRow(actions) {}
            if (app.projects.isNotEmpty()) {
                Caps("Saved projects")
                ProjectRows(app, actions) {}
            }
            Text("Projects save as you work.", fontSize = 12.sp, color = Mono.inkDim)
            if (app.status.isNotEmpty()) Text(app.status, fontSize = 13.sp, color = if (app.statusIsError) Mono.error else Mono.inkDim)
        }
        Scrollbar(scroll, true, Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight())
    }
}

/**
 * Switch, start, rename or delete projects from inside the editor.
 * [onDismiss] closes just this dialog; [onSwitched] also runs after a project
 * is started or opened, so the caller can close whatever sat behind it.
 */
@Composable
fun ProjectsDialog(app: AppState, actions: ProjectActions, onDismiss: () -> Unit, onSwitched: () -> Unit) {
    MonoDialog("Projects", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NewProjectRow(actions, onSwitched)
            ProjectRows(app, actions, onSwitched)
        }
    }
}
