package com.jezrel.syncnotes.sync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** POST /api/v1/sync/push (sections 8.3, 8.4). Duplicate change ids are rejected by the service. */
public record PushRequest(
        @NotNull UUID deviceId,
        @NotNull String deviceName,
        @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid ChangeDto> changes) {
}
