package com.jezrel.syncnotes.auth;

/** Wrong password or unknown email, deliberately not distinguished: 401 INVALID_CREDENTIALS. */
public class InvalidCredentialsException extends RuntimeException {
}
