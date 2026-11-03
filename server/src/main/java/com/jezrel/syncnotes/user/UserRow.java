package com.jezrel.syncnotes.user;

import java.util.UUID;

/** The sign-in columns of the {@code users} table. */
public record UserRow(UUID id, String email, String passwordHash) {
}
