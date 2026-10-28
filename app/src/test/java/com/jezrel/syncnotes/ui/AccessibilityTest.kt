package com.jezrel.syncnotes.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jezrel.syncnotes.data.local.NoteEntity
import com.jezrel.syncnotes.data.local.NoteSummary
import com.jezrel.syncnotes.ui.editor.NoteEditorContent
import com.jezrel.syncnotes.ui.noteslist.NotesListContent
import com.jezrel.syncnotes.ui.noteslist.NotesListUiState
import com.jezrel.syncnotes.ui.theme.SyncNotesTheme
import com.jezrel.syncnotes.ui.trash.TrashContent
import com.jezrel.syncnotes.ui.trash.TrashUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Step 1.8 checks that don't need a device: 48 dp touch targets, toggle state for pin
 * (not color alone), an "Open note" action label on rows, and named editor fields.
 * Renders the stateless content composables; SDK 33 for the Robolectric/Espresso workaround.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = listOf(
        NoteSummary("1", "Groceries", "eggs", pinned = true, updatedAt = 2, dirtyFields = 0, syncError = null),
        NoteSummary("2", "Trip", "Lisbon", pinned = false, updatedAt = 1, dirtyFields = 0, syncError = null),
    )

    /**
     * Checks the *touch* target, not the drawn size: Material 3 icon buttons draw at 40 dp and
     * extend their touch area to 48 dp via minimumInteractiveComponentSize. Touch bounds are
     * what accessibility services report and what the 48 dp guideline is about.
     */
    private fun SemanticsNodeInteraction.assertMinTouchTarget(): SemanticsNodeInteraction {
        val touch = fetchSemanticsNode().touchBoundsInRoot
        val (w, h) = with(compose.density) { touch.width.toDp() to touch.height.toDp() }
        assertTrue("touch target ${w}x$h is under 48dp", w >= 47.5.dp && h >= 47.5.dp)
        return this
    }

    private fun showList() = compose.setContent {
        SyncNotesTheme(dynamicColor = false) {
            NotesListContent(
                query = "", state = NotesListUiState(notes = notes, loading = false),
                onQueryChange = {}, onOpenNote = {}, onNewNote = {},
                onOpenTrash = {}, onOpenSettings = {}, onTogglePin = { _, _ -> },
            )
        }
    }

    @Test
    fun notesList_controlsMeetMinimumTouchTarget() {
        showList()
        compose.onNodeWithContentDescription("New note").assertMinTouchTarget()
        compose.onNodeWithContentDescription("More options").assertMinTouchTarget()
        compose.onAllNodesWithContentDescription("Pin")[0].assertMinTouchTarget()
    }

    @Test
    fun pinToggle_exposesOnOffState() {
        showList()
        val pins = compose.onAllNodesWithContentDescription("Pin")
        pins[0].assertIsOn()  // "Groceries" is pinned
        pins[1].assertIsOff() // "Trip" is not
        compose.onNodeWithText("Pinned").assertExists() // visible, non-color indicator
    }

    @Test
    fun noteRows_announceOpenAction() {
        showList()
        val opensNote = SemanticsMatcher("onClick labelled 'Open note'") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open note"
        }
        compose.onAllNodes(opensNote).assertCountEquals(notes.size)
    }

    @Test
    fun trash_controlsMeetMinimumTouchTarget() {
        compose.setContent {
            SyncNotesTheme(dynamicColor = false) {
                TrashContent(
                    state = TrashUiState(
                        notes = listOf(
                            NoteEntity("1", "Old", "body", pinned = false, trashed = true, createdAt = 0,
                                updatedAt = 0, serverVersion = 0, dirtyFields = 0, syncError = null),
                        ),
                        loading = false,
                    ),
                    onBack = {}, onRestore = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Back").assertMinTouchTarget()
        compose.onNodeWithContentDescription("Restore").assertMinTouchTarget()
    }

    @Test
    fun editor_controlsMeetMinimumTouchTarget_andFieldsAreNamed() {
        compose.setContent {
            SyncNotesTheme(dynamicColor = false) {
                NoteEditorContent(
                    title = TextFieldState("Groceries"), body = TextFieldState("eggs"),
                    pinned = true, trashed = false,
                    onBack = {}, onTogglePin = {}, onToggleTrash = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Back").assertMinTouchTarget()
        compose.onNodeWithContentDescription("Pin").assertMinTouchTarget().assertIsOn()
        compose.onNodeWithContentDescription("Move to trash").assertMinTouchTarget()
        compose.onNodeWithText("Title").assertExists() // field label, read by TalkBack once filled
        compose.onNodeWithText("Note").assertExists()
        compose.onNodeWithText("Pinned note").assertExists()
    }
}
