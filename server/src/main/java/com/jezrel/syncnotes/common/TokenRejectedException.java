package com.jezrel.syncnotes.common;

/**
 * A correctly signed token that still can't be used: its subject isn't a user id, or the account
 * no longer exists. Answered with 401 TOKEN_INVALID, like a bad signature.
 */
public class TokenRejectedException extends RuntimeException {
    public TokenRejectedException(String message) {
        super(message);
    }
}
