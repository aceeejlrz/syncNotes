package com.jezrel.syncnotes.sync;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of the {@code notes} table (ARCHITECTURE.md section 8.1), without its user id. */
public record NoteRow(
        UUID id,
        NoteValues values,
        PerField<Long> fieldVersions,
        PerField<UUID> fieldDevices,
        long serverVersion,
        Instant createdAt,
        Instant updatedAt) {

    public NoteRow {
        Objects.requireNonNull(id);
        Objects.requireNonNull(values);
        Objects.requireNonNull(fieldVersions);
        Objects.requireNonNull(fieldDevices);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
    }

    /** The view of this row that {@link MergeEngine} works on. */
    public ServerNote toServerNote() {
        return new ServerNote(values, fieldVersions, fieldDevices, serverVersion);
    }
}
