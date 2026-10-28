package com.jezrel.syncnotes.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * One change from a device (section 8.5). A null field means "not sent"; an empty string is a
 * real sent value. {@code baseVersions} always carries all four fields (ADR-018).
 */
public record IncomingChange(
        UUID id,
        PerField<Long> baseVersions,
        String title,
        String body,
        Boolean pinned,
        Boolean trashed) {

    public IncomingChange {
        Objects.requireNonNull(id);
        Objects.requireNonNull(baseVersions);
    }

    public boolean sends(NoteField field) {
        return sentValue(field) != null;
    }

    /** The sent value, or null when the field wasn't sent. */
    public Object sentValue(NoteField field) {
        return switch (field) {
            case TITLE -> title;
            case BODY -> body;
            case PINNED -> pinned;
            case TRASHED -> trashed;
        };
    }
}
