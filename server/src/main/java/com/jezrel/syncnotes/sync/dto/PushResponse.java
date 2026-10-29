package com.jezrel.syncnotes.sync.dto;

import java.util.List;

public record PushResponse(List<PushResultDto> results) {
}
