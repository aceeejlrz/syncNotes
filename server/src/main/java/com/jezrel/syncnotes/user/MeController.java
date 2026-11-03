package com.jezrel.syncnotes.user;

import com.jezrel.syncnotes.common.CurrentUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/me} (section 8.2). */
@RestController
public class MeController {

    public record MeResponse(UUID userId, String email) {
    }

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/v1/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = CurrentUser.id(jwt);
        UserRow user = users.findById(userId).orElseThrow(() -> new UnknownUserException(userId));
        return new MeResponse(user.id(), user.email());
    }
}
