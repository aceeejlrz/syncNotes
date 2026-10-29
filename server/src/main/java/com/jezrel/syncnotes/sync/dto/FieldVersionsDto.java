package com.jezrel.syncnotes.sync.dto;

import com.jezrel.syncnotes.sync.PerField;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** One version per field: a change's bases, or a note's field versions (ADR-018). */
public record FieldVersionsDto(
        @NotNull @PositiveOrZero Long title,
        @NotNull @PositiveOrZero Long body,
        @NotNull @PositiveOrZero Long pinned,
        @NotNull @PositiveOrZero Long trashed) {

    public static FieldVersionsDto from(PerField<Long> v) {
        return new FieldVersionsDto(v.title(), v.body(), v.pinned(), v.trashed());
    }

    public PerField<Long> toPerField() {
        return new PerField<>(title, body, pinned, trashed);
    }
}
