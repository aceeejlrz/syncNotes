package com.jezrel.syncnotes.common;

/** A request that fails validation as a whole (section 8.4): answered with 400 VALIDATION_FAILED. */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
