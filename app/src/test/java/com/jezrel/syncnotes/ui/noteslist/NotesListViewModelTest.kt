package com.jezrel.syncnotes.ui.noteslist

import androidx.compose.runtime.snapshots.Snapshot
import app.cash.turbine.test
import com.jezrel.syncnotes.testing.FakeNotesRepository
import com.jezrel.syncnotes.testing.FakeNotesRepository.Companion.note
import com.jezrel.syncnotes.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fake = FakeNotesRepository(
        listOf(note("1", "Groceries"), note("2", "Trip"), note("3", "Old", trashed = true)),
    )

    @Test
    fun emitsLoadingThenActiveNotes() = runTest {
        val vm = NotesListViewModel(fake)
        vm.uiState.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertEquals(setOf("1", "2"), loaded.notes.map { it.id }.toSet())
        }
    }

    @Test
    fun rapidTyping_queriesRepositoryOnceWithFinalText() = runTest {
        val vm = NotesListViewModel(fake)
        vm.uiState.test {
            awaitItem() // loading
            awaitItem() // loaded for the initial empty query
            assertEquals(listOf(""), fake.activeQueries)

            for (q in listOf("a", "ab", "abc")) {
                vm.onQueryChange(q)
                Snapshot.sendApplyNotifications() // let snapshotFlow see the new query
                runCurrent()                      // no time passes: debounce keeps restarting
            }
            advanceTimeBy(251) // quiet for the 250 ms debounce
            runCurrent()

            assertEquals(listOf("", "abc"), fake.activeQueries)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun togglePin_callsRepository() = runTest {
        val vm = NotesListViewModel(fake)
        vm.onTogglePin("1", true)
        advanceUntilIdle()
        assertEquals(listOf("1" to true), fake.pinCalls)
    }
}
