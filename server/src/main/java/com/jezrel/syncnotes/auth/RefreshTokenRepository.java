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

    /** The columns a rotation decides on. {@code replacedBy} is set only by a rotation. */
    public record Row(UUID id, UUID userId, UUID deviceId, Instant expiresAt, Instant revokedAt, UUID replacedBy) {
    }

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID userId, String tokenHash, UUID deviceId, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, expires_at)
                VALUES (:id, :userId, :hash, :deviceId, :expiresAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("deviceId", deviceId)
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /**
     * Finds a token and locks its row until the transaction ends, so concurrent calls with the same
     * token run one after another, and each sees what the one before it did.
     */
    public Optional<Row> lockByHash(String tokenHash) {
        return jdbc.sql("""
                SELECT id, user_id, device_id, expires_at, revoked_at, replaced_by
                FROM refresh_tokens WHERE token_hash = :hash FOR UPDATE
                """)
                .param("hash", tokenHash)
                .query((rs, rowNum) -> {
                    OffsetDateTime revokedAt = rs.getObject("revoked_at", OffsetDateTime.class);
                    return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                            rs.getObject("device_id", UUID.class),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            revokedAt == null ? null : revokedAt.toInstant(),
                            rs.getObject("replaced_by", UUID.class));
                })
                .optional();
    }

    /** Records the replacement. An earlier revocation time stays, so a retry can't extend the grace. */
    public void markReplaced(UUID id, UUID replacedBy, Instant now) {
        jdbc.sql("""
                UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, :now), replaced_by = :replacedBy
                WHERE id = :id
                """)
                .param("id", id)
                .param("replacedBy", replacedBy)
                .param("now", utc(now))
                .update();
    }

    /** @return false if the token was already revoked */
    public boolean revokeIfActive(UUID id, Instant now) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("id", id)
                .param("now", utc(now))
                .update() == 1;
    }

    public void clearReplacedBy(UUID id) {
        jdbc.sql("UPDATE refresh_tokens SET replaced_by = NULL WHERE id = :id")
                .param("id", id)
                .update();
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
