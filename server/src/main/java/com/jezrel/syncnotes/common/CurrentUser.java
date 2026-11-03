package com.jezrel.syncnotes.common;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The user id of a request: always from the verified token, never from the request itself. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id(Jwt jwt) {
        String subject = jwt.getSubject();
        try {
            if (subject != null) {
                return UUID.fromString(subject);
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw new TokenRejectedException("Token subject is not a user id");
    }
}
