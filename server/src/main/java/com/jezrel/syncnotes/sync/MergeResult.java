package com.jezrel.syncnotes.sync;

import java.util.Set;

/**
 * Result of merging one change. {@code before} is null when the note didn't exist (M1).
 * Stamping change numbers and creating the copy's row is the sync service's job.
 */
public record MergeResult(
        NoteValues before,
        NoteValues after,
        Set<NoteField> changedFields,
        ConflictCopy conflictCopy,
        PushStatus status,
        Set<MergeRule> rules) {

    public MergeResult {
        changedFields = Set.copyOf(changedFields);
        rules = Set.copyOf(rules);
    }

    /** Whether the main note must be written (and stamped with a new change number). */
    public boolean mainChanged() {
        return !changedFields.isEmpty();
    }
}
