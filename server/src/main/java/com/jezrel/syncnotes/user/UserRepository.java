package com.jezrel.syncnotes.user;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL for the {@code users} table (section 8.1), including the per-user change counter (9.3). */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a user; {@code email} must already be normalized (lowercased) by the caller. */
    public void create(UUID id, String email, String passwordHash) {
        jdbc.sql("INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", id)
                .param("email", email)
                .param("hash", passwordHash)
                .update();
    }

    /**
     * Locks the user's row and returns its change counter. A second push for the same user waits
     * here until this transaction ends, so change numbers become visible in order and a pull can
     * never skip one (section 9.3). Pushes for different users don't block each other.
     *
     * @throws UnknownUserException if the account doesn't exist (a valid token for a deleted user)
     * @throws IllegalStateException outside a transaction, where the lock would be released at
     *     once and silently stop protecting the counter
     */
    public long lockAndGetSyncVersion(UUID userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("lockAndGetSyncVersion must run inside a transaction");
        }
        return jdbc.sql("SELECT sync_version FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new UnknownUserException(userId));
    }

    public void setSyncVersion(UUID userId, long syncVersion) {
        jdbc.sql("UPDATE users SET sync_version = :v WHERE id = :id")
                .param("v", syncVersion)
                .param("id", userId)
                .update();
    }
}
