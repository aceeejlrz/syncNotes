package com.jezrel.syncnotes.sync;

import java.util.Objects;

/** One value per {@link NoteField}: field versions, field devices or base versions. */
public record PerField<T>(T title, T body, T pinned, T trashed) {

    public PerField {
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
        Objects.requireNonNull(pinned);
        Objects.requireNonNull(trashed);
    }

    public static <T> PerField<T> all(T value) {
        return new PerField<>(value, value, value, value);
    }

    public PerField<T> with(NoteField field, T value) {
        return switch (field) {
            case TITLE -> new PerField<>(value, body, pinned, trashed);
            case BODY -> new PerField<>(title, value, pinned, trashed);
            case PINNED -> new PerField<>(title, body, value, trashed);
            case TRASHED -> new PerField<>(title, body, pinned, value);
        };
    }

    public T get(NoteField field) {
        return switch (field) {
            case TITLE -> title;
            case BODY -> body;
            case PINNED -> pinned;
            case TRASHED -> trashed;
        };
    }
}
