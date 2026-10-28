package com.jezrel.syncnotes.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * A note as the server stores it: values plus, per field, the change number of its last write
 * and the device that made it (section 8.1), and the row's server version.
 */
public record ServerNote(
        NoteValues values,
        PerField<Long> fieldVersions,
        PerField<UUID> fieldDevices,
        long serverVersion) {

    public ServerNote {
        Objects.requireNonNull(values);
        Objects.requireNonNull(fieldVersions);
        Objects.requireNonNull(fieldDevices);
    }
}
