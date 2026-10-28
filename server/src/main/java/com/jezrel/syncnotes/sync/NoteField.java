package com.jezrel.syncnotes.sync;

import java.util.EnumSet;
import java.util.Set;

/** The four syncable fields of a note (ARCHITECTURE.md section 7.2). */
public enum NoteField {
    TITLE, BODY, PINNED, TRASHED;

    /** Title and body: the fields a true conflict (M4) is about. */
    public static final Set<NoteField> CONTENT = EnumSet.of(TITLE, BODY);
}
