package com.jezrel.syncnotes.auth;

import com.jezrel.syncnotes.common.TokenRejectedException;
import com.jezrel.syncnotes.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Access JWTs and refresh token rotation (sections 10.2, 10.4). */
@Service
public class TokenService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    /** The result of a rotation: whose token it was, and the token that replaces it. */
    public record Rotated(UUID userId, String refreshToken) {
    }

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final AuthProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshTokens, AuthProperties props, Clock clock) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.props = props;
        this.clock = clock;
    }

    public String newAccessToken(UUID userId) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .build();
        // The explicit header matters: the default is RS256, which fails with a secret key (10.4).
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    public long accessTokenExpiresInSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    /** Stores the hash of a new refresh token and returns the raw token, which is never stored. */
    public String newRefreshToken(UUID userId, UUID deviceId) {
        return insertNew(UUID.randomUUID(), userId, deviceId);
    }

    private String insertNew(UUID id, UUID userId, UUID deviceId) {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.insert(id, userId, sha256(token), deviceId, Instant.now(clock).plus(props.refreshTokenTtl()));
        return token;
    }

    /**
     * Revokes the given refresh token and issues its replacement in one transaction.
     *
     * <p>Grace period (section 10.2): the old token is revoked before the response reaches the phone,
     * so a lost response would leave the phone with nothing that works. For a short time after a
     * rotation the old token can therefore be used again, which cancels the replacement that never
     * arrived, so one token stays live. If that replacement was already used, the phone did receive
     * it and the retry is refused.
     *
     * @throws TokenRejectedException if the token is unknown, expired or revoked outside the grace
     */
    @Transactional
    public Rotated rotate(String refreshToken) {
        Instant now = Instant.now(clock);
        RefreshTokenRepository.Row old = refreshTokens.lockByHash(sha256(refreshToken))
                .filter(row -> row.expiresAt().isAfter(now))
                .filter(row -> row.revokedAt() == null || cancelUnusedReplacement(row, now))
                .orElseThrow(() -> new TokenRejectedException("Refresh token is not usable"));
        refreshTokens.deleteExpired(old.userId(), now);
        UUID replacement = UUID.randomUUID();
        String token = insertNew(replacement, old.userId(), old.deviceId());
        refreshTokens.markReplaced(old.id(), replacement, now);
        return new Rotated(old.userId(), token);
    }

    private boolean cancelUnusedReplacement(RefreshTokenRepository.Row row, Instant now) {
        return inGrace(row, now) && refreshTokens.revokeIfActive(row.replacedBy(), now);
    }

    /** A token revoked by logout or by hand has no replacement, so it is never in its grace period. */
    private boolean inGrace(RefreshTokenRepository.Row row, Instant now) {
        return row.replacedBy() != null && row.revokedAt().plus(props.refreshTokenGrace()).isAfter(now);
    }

    /**
     * Logout. Unknown or already revoked tokens are fine: the result is the same. Signing out with a
     * token still in its grace period also ends the replacement the phone never received.
     */
    @Transactional
    public void revoke(String refreshToken) {
        Instant now = Instant.now(clock);
        refreshTokens.lockByHash(sha256(refreshToken)).ifPresent(row -> {
            if (row.revokedAt() == null) {
                refreshTokens.revokeIfActive(row.id(), now);
            } else if (inGrace(row, now)) {
                refreshTokens.revokeIfActive(row.replacedBy(), now);
                refreshTokens.clearReplacedBy(row.id());
            }
        });
    }

    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM must provide SHA-256", e);
        }
    }
}
