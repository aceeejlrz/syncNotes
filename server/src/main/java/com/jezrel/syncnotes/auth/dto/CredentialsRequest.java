package com.jezrel.syncnotes.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;

/**
 * Register or login (section 8.3). The email is normalized here, so validation and every query
 * see the stored form. The password rules apply only to new accounts: see AuthService.
 */
public record CredentialsRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull String password,
        @NotNull UUID deviceId) {

    public CredentialsRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "CredentialsRequest[email=" + email + ", deviceId=" + deviceId + "]";   // never the password
    }
}
