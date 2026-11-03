package com.jezrel.syncnotes.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
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

    /**
     * Creates a user; {@code email} must already be normalized (lowercased) by the caller.
     *
     * @return false if the email already has an account. ON CONFLICT also settles two concurrent
     *     registrations, without an exception that would abort the caller's transaction
     */
    public boolean create(UUID id, String email, String passwordHash) {
        return jdbc.sql("""
                INSERT INTO users (id, email, password_hash) VALUES (:id, :email, :hash)
                ON CONFLICT (email) DO NOTHING
                """)
                .param("id", id)
                .param("email", email)
                .param("hash", passwordHash)
                .update() == 1;
    }

    public Optional<UserRow> findById(UUID id) {
        return jdbc.sql("SELECT id, email, password_hash FROM users WHERE id = :id")
                .param("id", id)
                .query(UserRepository::mapUser)
                .optional();
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash FROM users WHERE email = :email")
                .param("email", email)
                .query(UserRepository::mapUser)
                .optional();
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

    private static UserRow mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"));
    }
}
