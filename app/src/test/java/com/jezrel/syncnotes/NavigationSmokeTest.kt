package com.jezrel.syncnotes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Runtime smoke test: launches the real MainActivity so the Hilt graph, Navigation 3,
 * and the notes list actually execute on the JVM (compile-checking can't prove this).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class NavigationSmokeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    // Pinned to SDK 33: createAndroidComposeRule wires Espresso, whose input strategy calls
    // InputManager.getInstance(), which Robolectric doesn't shadow at SDK 37. Assertions only
    // (no click) — on-device navigation is on the manual checklist. Still proves the whole
    // DI + Navigation 3 + Compose stack assembles and renders at runtime.
    @Test
    fun launchesToNotesList() {
        compose.onNodeWithText("SyncNotes").assertIsDisplayed()
        compose.onNodeWithContentDescription("New note").assertIsDisplayed()
    }
}
