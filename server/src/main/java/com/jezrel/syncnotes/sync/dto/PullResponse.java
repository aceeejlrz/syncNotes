package com.jezrel.syncnotes.sync.dto;

import java.util.List;

/** GET /api/v1/sync/pull (section 9.6). */
public record PullResponse(List<NoteDto> notes, long nextCursor, boolean hasMore) {
}
