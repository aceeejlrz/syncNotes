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
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.insert(userId, sha256(token), deviceId, Instant.now(clock).plus(props.refreshTokenTtl()));
        return token;
    }

    /**
     * Revokes the given refresh token and issues its replacement in one transaction.
     *
     * @throws TokenRejectedException if the token is unknown, expired or already revoked
     */
    @Transactional
    public Rotated rotate(String refreshToken) {
        Instant now = Instant.now(clock);
        RefreshTokenRepository.Owner owner = refreshTokens.revokeIfActive(sha256(refreshToken), now)
                .orElseThrow(() -> new TokenRejectedException("Refresh token is not usable"));
        refreshTokens.deleteExpired(owner.userId(), now);
        return new Rotated(owner.userId(), newRefreshToken(owner.userId(), owner.deviceId()));
    }

    /** Logout. Unknown or already revoked tokens are fine: the result is the same. */
    public void revoke(String refreshToken) {
        refreshTokens.revokeIfActive(sha256(refreshToken), Instant.now(clock));
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
