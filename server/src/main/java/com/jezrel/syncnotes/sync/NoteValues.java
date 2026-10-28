package com.jezrel.syncnotes.sync;

import java.util.Objects;

/** The user-visible content of a note. */
public record NoteValues(String title, String body, boolean pinned, boolean trashed) {

    public NoteValues {
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
    }

    public Object get(NoteField field) {
        return switch (field) {
            case TITLE -> title;
            case BODY -> body;
            case PINNED -> pinned;
            case TRASHED -> trashed;
        };
    }

    public NoteValues with(NoteField field, Object value) {
        return switch (field) {
            case TITLE -> new NoteValues((String) value, body, pinned, trashed);
            case BODY -> new NoteValues(title, (String) value, pinned, trashed);
            case PINNED -> new NoteValues(title, body, (Boolean) value, trashed);
            case TRASHED -> new NoteValues(title, body, pinned, (Boolean) value);
        };
    }
}
