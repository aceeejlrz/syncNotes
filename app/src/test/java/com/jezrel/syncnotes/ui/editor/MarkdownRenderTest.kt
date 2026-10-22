package com.jezrel.syncnotes.ui.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme
import com.mikepenz.markdown.m3.Markdown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the pinned markdown renderer (0.41.0) against the Compose 1.10 runtime, so any
 * binary incompatibility surfaces here rather than as a NoSuchMethodError on a user's device.
 * SDK 33: same Robolectric/Espresso workaround as the other UI tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MarkdownRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersMarkdownHeading() {
        compose.setContent { SyncNotesTheme { Markdown("# hi") } }
        // assertExists (not assertIsDisplayed): proves the renderer parsed the markdown and
        // produced the "hi" node, without depending on headless-Robolectric layout bounds.
        compose.onNodeWithText("hi").assertExists()
    }
}
