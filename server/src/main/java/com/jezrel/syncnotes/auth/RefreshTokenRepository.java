package com.jezrel.syncnotes.auth;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code refresh_tokens} (section 8.1). Only SHA-256 hashes are ever stored. */
@Repository
public class RefreshTokenRepository {

    /** Who a revoked token belonged to. */
    public record Owner(UUID userId, UUID deviceId) {
    }

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, String tokenHash, UUID deviceId, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, expires_at)
                VALUES (:id, :userId, :hash, :deviceId, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("deviceId", deviceId)
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /**
     * Revokes the token if it is still usable and says whose it was. One atomic statement: of two
     * concurrent calls with the same token, the second waits for the first and then matches nothing.
     */
    public Optional<Owner> revokeIfActive(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE refresh_tokens SET revoked_at = :now
                WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
                RETURNING user_id, device_id
                """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query((rs, rowNum) -> new Owner(rs.getObject("user_id", UUID.class),
                        rs.getObject("device_id", UUID.class)))
                .optional();
    }

    /**
     * Removes this user's tokens past their expiry. Revoked ones stay until then, so the table
     * stays bounded while the reuse detection stretch goal (section 10.2) can still recognize them.
     */
    public void deleteExpired(UUID userId, Instant now) {
        jdbc.sql("DELETE FROM refresh_tokens WHERE user_id = :userId AND expires_at <= :now")
                .param("userId", userId)
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
