package com.jezrel.syncnotes.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Refresh or logout (section 8.2). */
public record RefreshTokenRequest(@NotBlank String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest[]";                                     // never the token
    }
}
