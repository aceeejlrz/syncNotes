package com.jezrel.syncnotes.user;

import java.util.UUID;

/** No {@code users} row for this id, e.g. a still-valid token whose account was deleted. */
public class UnknownUserException extends RuntimeException {
    public UnknownUserException(UUID userId) {
        super("No account " + userId);
    }
}
