package com.jezrel.syncnotes.auth.dto;

import java.util.UUID;

/** Returned by register, login and refresh (section 8.3). */
public record AuthResponse(
        UUID userId,
        String email,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken) {
}
