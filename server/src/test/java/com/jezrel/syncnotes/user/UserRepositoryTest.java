package com.jezrel.syncnotes.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jezrel.syncnotes.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** UserRepository and the per-user row lock (section 9.3) against real PostgreSQL 17. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

    static final UUID ALICE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired UserRepository users;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
        users.create(ALICE, "alice@example.com", "hash");
        users.create(BOB, "bob@example.com", "hash");
    }

    @Test
    void newUser_startsAtZero_andTheCounterPersists() {
        Long initial = tx.execute(s -> users.lockAndGetSyncVersion(ALICE));
        assertThat(initial).isZero();
        tx.executeWithoutResult(s -> users.setSyncVersion(ALICE, 42));
        Long updated = tx.execute(s -> users.lockAndGetSyncVersion(ALICE));
        assertThat(updated).isEqualTo(42L);
    }

    @Test
    void lock_refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> users.lockAndGetSyncVersion(ALICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside a transaction");
    }

    @Test
    void lock_blocksASecondTransactionForTheSameUser() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            users.lockAndGetSyncVersion(ALICE);
            locked.countDown();
            await(release);                                   // hold the row lock
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // A concurrent push for the same user must wait; give up after 300 ms to prove it would.
            // PostgreSQL reports SQLSTATE 55P03 (lock_not_available); Spring leaves it uncategorized.
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                jdbc.sql("SET LOCAL lock_timeout = '300ms'").update();
                users.lockAndGetSyncVersion(ALICE);
            }))
                    .isInstanceOf(UncategorizedSQLException.class)
                    .extracting(e -> ((UncategorizedSQLException) e).getSQLException().getSQLState())
                    .isEqualTo(LOCK_NOT_AVAILABLE);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void lock_doesNotBlockOtherUsers() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            users.lockAndGetSyncVersion(ALICE);
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Long bobs = tx.execute(s -> {
                jdbc.sql("SET LOCAL lock_timeout = '300ms'").update();
                return users.lockAndGetSyncVersion(BOB);      // different row: no wait
            });
            assertThat(bobs).isZero();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
