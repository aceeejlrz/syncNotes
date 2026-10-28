package com.jezrel.syncnotes.ui.trash

import app.cash.turbine.test
import com.jezrel.syncnotes.testing.FakeNotesRepository
import com.jezrel.syncnotes.testing.FakeNotesRepository.Companion.note
import com.jezrel.syncnotes.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrashViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fake = FakeNotesRepository(listOf(note("a", "Active"), note("t", "Old", trashed = true)))

    @Test
    fun emitsLoadingThenOnlyTrashedNotes() = runTest {
        val vm = TrashViewModel(fake)
        vm.uiState.test {
            assertTrue(awaitItem().loading)
            assertEquals(listOf("t"), awaitItem().notes.map { it.id })
        }
    }

    @Test
    fun restore_callsRepository_andNoteLeavesTrash() = runTest {
        val vm = TrashViewModel(fake)
        vm.uiState.test {
            awaitItem() // loading
            assertEquals(listOf("t"), awaitItem().notes.map { it.id })

            vm.onRestore("t")

            assertTrue(awaitItem().notes.isEmpty())
            assertEquals(listOf("t"), fake.restoreCalls)
        }
    }
}
