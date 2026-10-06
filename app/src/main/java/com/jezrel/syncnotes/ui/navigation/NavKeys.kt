package com.jezrel.syncnotes.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Navigation 3 destinations. Each screen is a @Serializable key (ARCHITECTURE.md §6.3). */
@Serializable data object NotesList : NavKey

@Serializable data class NoteEditor(val noteId: String, val isNew: Boolean = false) : NavKey

@Serializable data object Trash : NavKey

@Serializable data object Settings : NavKey

@Serializable data object SignIn : NavKey
