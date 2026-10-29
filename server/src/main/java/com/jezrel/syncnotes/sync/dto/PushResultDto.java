package com.jezrel.syncnotes.sync.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jezrel.syncnotes.sync.PushStatus;
import java.util.UUID;

/** One result per change. {@code note} is absent for REJECTED, {@code error} for the rest. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushResultDto(UUID id, PushStatus status, NoteDto note, String error) {

    public static PushResultDto of(UUID id, PushStatus status, NoteDto note) {
        return new PushResultDto(id, status, note, null);
    }

    public static PushResultDto rejected(UUID id, String error) {
        return new PushResultDto(id, PushStatus.REJECTED, null, error);
    }
}
