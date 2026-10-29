package com.jezrel.syncnotes.sync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** One note change in a push (section 8.5). A null field means "not sent". */
public record ChangeDto(
        @NotNull UUID id,
        @NotNull @Valid FieldVersionsDto baseVersions,
        @NotNull Instant createdAt,
        String title,
        String body,
        Boolean pinned,
        Boolean trashed) {
}
