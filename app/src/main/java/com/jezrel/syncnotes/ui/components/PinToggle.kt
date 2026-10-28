package com.jezrel.syncnotes.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Pin/unpin toggle used by the list and the editor. Toggle semantics make TalkBack announce
 * "Pin, on/off"; the tint is only a secondary cue (callers also show a visible "Pinned" label).
 * Core icons have no hollow star, so the glyph is the same in both states.
 */
@Composable
fun PinToggle(pinned: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    IconToggleButton(checked = pinned, onCheckedChange = { onToggle() }, enabled = enabled) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = "Pin",
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
