package com.hereliesaz.morphont

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Theme and view settings survive a round trip through the store and back onto a fresh app. */
class SettingsTest {
    @AfterTest fun restoreTheme() { ThemeState.dark = true }

    @Test fun settingsRoundTripThroughTheStoreAndOntoAFreshApp() {
        var saved: String? = null
        val store = SettingsStore({ saved }, { saved = it })
        val app = AppState()
        app.view.snap = false
        app.view.showTravel = true
        app.view.showGrid = true
        app.view.gridStep = 25f
        app.view.glyphOpacity = 0.3f
        app.setNodeMoveCircleEnabled(true)
        ThemeState.dark = false
        store.save(app.settings())
        assertTrue(saved != null)

        ThemeState.dark = true
        val fresh = AppState()
        fresh.applySettings(store.load())
        assertTrue(!ThemeState.dark)
        assertEquals(false, fresh.view.snap)
        assertEquals(true, fresh.view.showTravel)
        assertEquals(true, fresh.view.showGrid)
        assertEquals(25f, fresh.view.gridStep)
        assertEquals(0.3f, fresh.view.glyphOpacity)
        assertEquals(true, fresh.nodeMoveCircleEnabled)
    }

    @Test fun missingOrBrokenSettingsFallBackToTheDefaults() {
        assertEquals(AppSettings(), SettingsStore({ null }, {}).load())
        assertEquals(AppSettings(), SettingsStore({ "not json" }, {}).load())
        assertEquals(AppSettings(), SettingsStore({ throw IllegalStateException("storage gone") }, {}).load())
        // A file from an older or newer build: unknown keys are ignored, missing ones default.
        assertEquals(AppSettings(dark = false), SettingsStore({ """{"dark":false,"somethingNew":1}""" }, {}).load())
    }

    @Test fun nonsenseValuesAreClamped() {
        val app = AppState()
        app.applySettings(AppSettings(gridStep = -5f, glyphOpacity = 7f))
        assertEquals(50f, app.view.gridStep)
        assertEquals(1f, app.view.glyphOpacity)
    }
}
