package com.jezrel.syncnotes.sync;

import com.jezrel.syncnotes.common.TokenRejectedException;
import com.jezrel.syncnotes.sync.dto.PullResponse;
import com.jezrel.syncnotes.sync.dto.PushRequest;
import com.jezrel.syncnotes.sync.dto.PushResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/sync} (section 8.2). The user id always comes from the token, never the request. */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/push")
    public PushResponse push(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PushRequest request) {
        return sync.push(userId(jwt), request);
    }

    @GetMapping("/pull")
    public PullResponse pull(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(defaultValue = "0") long since,
                             @RequestParam(defaultValue = "" + SyncService.PULL_DEFAULT) int limit) {
        return sync.pull(userId(jwt), since, limit);
    }

    private static UUID userId(Jwt jwt) {
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
