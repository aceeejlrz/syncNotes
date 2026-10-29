package com.jezrel.syncnotes.sync.dto;

import com.jezrel.syncnotes.sync.NoteRow;
import java.time.Instant;
import java.util.UUID;

/** A note's full server state, as returned by push results and pulls (section 8.3). */
public record NoteDto(
        UUID id,
        String title,
        String body,
        boolean pinned,
        boolean trashed,
        Instant createdAt,
        Instant updatedAt,
        FieldVersionsDto fieldVersions,
        long version) {

    public static NoteDto from(NoteRow row) {
        return new NoteDto(row.id(), row.values().title(), row.values().body(), row.values().pinned(),
                row.values().trashed(), row.createdAt(), row.updatedAt(),
                FieldVersionsDto.from(row.fieldVersions()), row.serverVersion());
    }
}
