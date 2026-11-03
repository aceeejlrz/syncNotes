package com.jezrel.syncnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 2.8: the whole server as a client sees it, over real HTTP through real Tomcat, against real
 * PostgreSQL 17. Everything here goes through the public API; SQL is only used to check the
 * stored result or to set up a state the API can't reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SyncEndToEndTest {

    static final String PASSWORD = "correct horse";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JwtEncoder encoder;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
    }

    // ---- a small API client -------------------------------------------------------------------

    record Reply(int status, JsonNode body, HttpResponse<String> raw) {
    }

    /** One signed-in device. */
    record Device(UUID id, String name, String accessToken, String refreshToken) {
    }

    Reply send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(120))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> raw = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(raw.statusCode(), raw.body().isEmpty() ? null : JSON.readTree(raw.body()), raw);
    }

    Device signIn(String path, String email, String name) throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = send("POST", "/api/v1/auth/" + path, null,
                Map.of("email", email, "password", PASSWORD, "deviceId", id.toString()));
        assertThat(r.status()).isIn(200, 201);
        return new Device(id, name, r.body().get("accessToken").asString(), r.body().get("refreshToken").asString());
    }

    Device register(String email, String name) throws Exception {
        return signIn("register", email, name);
    }

    static Map<String, Object> bases(long title, long body, long pinned, long trashed) {
        return Map.of("title", title, "body", body, "pinned", pinned, "trashed", trashed);
    }

    /** A change; {@code fields} holds only what is being sent, as the app sends dirty fields. */
    static Map<String, Object> change(UUID id, Map<String, Object> bases, Map<String, Object> fields) {
        Map<String, Object> c = new LinkedHashMap<>(fields);
        c.put("id", id.toString());
        c.put("baseVersions", bases);
        c.put("createdAt", "2026-10-06T09:12:40Z");
        return c;
    }

    static Map<String, Object> newNote(UUID id, String title, String body) {
        return change(id, bases(0, 0, 0, 0), Map.of("title", title, "body", body, "pinned", false, "trashed", false));
    }

    Reply push(Device d, List<Map<String, Object>> changes) throws Exception {
        return send("POST", "/api/v1/sync/push", d.accessToken(),
                Map.of("deviceId", d.id().toString(), "deviceName", d.name(), "changes", changes));
    }

    Reply pull(Device d, long since, int limit) throws Exception {
        return send("GET", "/api/v1/sync/pull?since=" + since + "&limit=" + limit, d.accessToken(), null);
    }

    /** Follows the cursor to the end, as the app does, and returns every note state it received. */
    List<JsonNode> pullAll(Device d, long since) throws Exception {
        List<JsonNode> notes = new ArrayList<>();
        long cursor = since;
        boolean more = true;
        while (more) {
            Reply r = pull(d, cursor, 200);
            assertThat(r.status()).isEqualTo(200);
            r.body().get("notes").forEach(notes::add);
            cursor = r.body().get("nextCursor").asLong();
            more = r.body().get("hasMore").asBoolean();
        }
        return notes;
    }

    long counter(String email) {
        return jdbc.sql("SELECT sync_version FROM users WHERE email = :e").param("e", email).query(Long.class).single();
    }

    static List<Long> versions(List<JsonNode> notes) {
        return notes.stream().map(n -> n.get("version").asLong()).toList();
    }

    /** Runs the tasks at the same moment and returns their results, failing if any task failed. */
    static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    void registerPushPull_acrossTwoDevices() throws Exception {
        Device phone = register("you@example.com", "Phone");
        Device tablet = signIn("login", "you@example.com", "Tablet");
        UUID id = UUID.randomUUID();

        // The phone creates a note.
        Reply created = push(phone, List.of(newNote(id, "Groceries", "- eggs")));
        assertThat(created.status()).isEqualTo(200);
        JsonNode pushed = created.body().get("results").get(0);
        assertThat(pushed.get("status").asString()).isEqualTo("APPLIED");
        assertThat(pushed.get("note").get("createdAt").asString()).isEqualTo("2026-10-06T09:12:40Z");

        // The tablet receives exactly what the phone was told.
        Reply pulled = pull(tablet, 0, 200);
        assertThat(pulled.body().get("notes").get(0)).isEqualTo(pushed.get("note"));
        assertThat(pulled.body().get("nextCursor").asLong()).isEqualTo(1);
        assertThat(pulled.body().get("hasMore").asBoolean()).isFalse();

        // The tablet pins it and sends only that field.
        JsonNode pinned = push(tablet, List.of(change(id, bases(1, 1, 1, 1), Map.of("pinned", true))))
                .body().get("results").get(0).get("note");
        assertThat(pinned.get("version").asLong()).isEqualTo(2);
        assertThat(pinned.get("body").asString()).isEqualTo("- eggs");
        assertThat(pinned.get("fieldVersions").get("pinned").asLong()).isEqualTo(2);
        assertThat(pinned.get("fieldVersions").get("body").asLong()).isEqualTo(1);

        // The phone gets the pin. A retry of that same push changes nothing.
        assertThat(pull(phone, 1, 200).body().get("notes").get(0)).isEqualTo(pinned);
        JsonNode retry = push(tablet, List.of(change(id, bases(1, 1, 1, 1), Map.of("pinned", true))))
                .body().get("results").get(0);
        assertThat(retry.get("status").asString()).isEqualTo("NOOP");
        assertThat(counter("you@example.com")).isEqualTo(2);

        // A deletion reaches other devices the same way an edit does.
        push(phone, List.of(change(id, bases(1, 1, 2, 1), Map.of("trashed", true))));
        JsonNode trashed = pull(tablet, 2, 200).body().get("notes").get(0);
        assertThat(trashed.get("trashed").asBoolean()).isTrue();
        assertThat(trashed.get("pinned").asBoolean()).isTrue();
    }

    @Test
    void aConflict_keepsBothTexts_andTheCopyArrivesOnTheNextPull() throws Exception {
        Device phone = register("you@example.com", "Phone");
        Device tablet = signIn("login", "you@example.com", "Tablet");
        UUID id = UUID.randomUUID();
        push(phone, List.of(newNote(id, "Groceries", "eggs")));

        push(phone, List.of(change(id, bases(1, 1, 1, 1), Map.of("body", "eggs, milk"))));
        JsonNode result = push(tablet, List.of(change(id, bases(1, 1, 1, 1), Map.of("body", "eggs, bread"))))
                .body().get("results").get(0);

        assertThat(result.get("status").asString()).isEqualTo("CONFLICT_COPY");
        assertThat(result.get("note").get("body").asString()).isEqualTo("eggs, milk");
        List<JsonNode> copies = pullAll(phone, result.get("note").get("version").asLong());
        assertThat(copies).hasSize(1);
        assertThat(copies.getFirst().get("title").asString()).isEqualTo("Groceries (conflicted copy from Tablet)");
        assertThat(copies.getFirst().get("body").asString()).isEqualTo("eggs, bread");
    }

    // ---- pagination and change numbers --------------------------------------------------------

    @Test
    void pagination_deliversEveryNoteOnce_inStrictlyIncreasingOrder() throws Exception {
        Device phone = register("you@example.com", "Phone");
        Set<String> sent = new HashSet<>();
        for (int batch = 0; batch < 3; batch++) {
            List<Map<String, Object>> changes = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                UUID id = UUID.randomUUID();
                sent.add(id.toString());
                changes.add(newNote(id, "n" + i, ""));
            }
            assertThat(push(phone, changes).status()).isEqualTo(200);
        }

        List<JsonNode> received = new ArrayList<>();
        long cursor = 0;
        int pages = 0;
        boolean more = true;
        while (more) {
            JsonNode page = pull(phone, cursor, 40).body();
            page.get("notes").forEach(received::add);
            assertThat(page.get("notes").size()).isLessThanOrEqualTo(40);
            cursor = page.get("nextCursor").asLong();
            more = page.get("hasMore").asBoolean();
            pages++;
        }

        assertThat(pages).isEqualTo(4);                                       // 40 + 40 + 40 + 30
        assertThat(versions(received)).isSorted().doesNotHaveDuplicates().hasSize(150);
        assertThat(received.stream().map(n -> n.get("id").asString()).toList()).containsExactlyInAnyOrderElementsOf(sent);
        assertThat(cursor).isEqualTo(counter("you@example.com"));
        assertThat(pull(phone, cursor, 40).body().get("notes").size()).isZero();
    }

    @Test
    void anEditedNote_movesToTheEndOfTheChanges() throws Exception {
        Device phone = register("you@example.com", "Phone");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        push(phone, List.of(newNote(first, "first", ""), newNote(second, "second", "")));

        push(phone, List.of(change(first, bases(1, 1, 1, 1), Map.of("title", "first, edited"))));

        List<JsonNode> all = pullAll(phone, 0);
        assertThat(versions(all)).containsExactly(2L, 3L);                    // number 1 no longer exists
        assertThat(all.get(1).get("title").asString()).isEqualTo("first, edited");
    }

    // ---- concurrency (section 9.3) -------------------------------------------------------------

    /**
     * The reason for the row lock. Writers push new notes at the same time while a reader follows
     * the cursor. Numbers must become visible in order: if number 5 could commit before number 4,
     * the reader would move its cursor past 4 and never see that note.
     */
    @Test
    void concurrentPushes_forOneUser_getGapFreeNumbers_andAPullNeverSkipsOne() throws Exception {
        int writers = 8;
        int pushesEach = 10;
        int notesEach = 5;
        int total = writers * pushesEach * notesEach;
        Device device = register("you@example.com", "Phone");

        AtomicBoolean writing = new AtomicBoolean(true);
        Callable<List<Long>> reader = () -> {
            List<Long> seen = new ArrayList<>();
            long cursor = 0;
            while (true) {
                boolean lastRound = !writing.get();                     // read before pulling
                JsonNode page = pull(device, cursor, 37).body();
                for (JsonNode note : page.get("notes")) {
                    long version = note.get("version").asLong();
                    assertThat(version).as("a pull skipped a change number").isEqualTo(cursor + 1);
                    cursor = version;
                    seen.add(version);
                }
                if (lastRound && !page.get("hasMore").asBoolean()) {
                    return seen;
                }
            }
        };

        ExecutorService readerThread = Executors.newSingleThreadExecutor();
        Future<List<Long>> seen = readerThread.submit(reader);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            tasks.add(() -> {
                for (int p = 0; p < pushesEach; p++) {
                    List<Map<String, Object>> changes = new ArrayList<>();
                    for (int n = 0; n < notesEach; n++) {
                        changes.add(newNote(UUID.randomUUID(), "n", ""));
                    }
                    Reply r = push(device, changes);
                    assertThat(r.status()).isEqualTo(200);
                }
                return 1;
            });
        }
        try {
            together(tasks);
        } finally {
            writing.set(false);
        }
        List<Long> versionsSeen = seen.get(120, TimeUnit.SECONDS);
        readerThread.shutdownNow();

        assertThat(versionsSeen).hasSize(total);
        assertThat(versionsSeen.getLast()).isEqualTo(total);
        assertThat(counter("you@example.com")).isEqualTo(total);
    }

    /** Different devices edit the same text from the same base, at once: no text may be lost. */
    @Test
    void concurrentEditsOfOneNote_oneWins_andEveryOtherTextBecomesACopy() throws Exception {
        Device first = register("you@example.com", "Phone");
        UUID id = UUID.randomUUID();
        push(first, List.of(newNote(id, "Groceries", "eggs")));

        int devices = 6;
        List<Callable<String>> tasks = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        for (int i = 0; i < devices; i++) {
            String text = "text from device " + i;
            texts.add(text);
            Device d = signIn("login", "you@example.com", "Device " + i);
            tasks.add(() -> {
                Reply r = push(d, List.of(change(id, bases(1, 1, 1, 1), Map.of("body", text))));
                assertThat(r.status()).isEqualTo(200);
                return r.body().get("results").get(0).get("status").asString();
            });
        }

        List<String> statuses = together(tasks);

        assertThat(statuses).filteredOn("APPLIED"::equals).hasSize(1);
        assertThat(statuses).filteredOn("CONFLICT_COPY"::equals).hasSize(devices - 1);
        List<JsonNode> all = pullAll(first, 0);
        assertThat(all).hasSize(devices);                                     // the note + 5 copies
        assertThat(all.stream().map(n -> n.get("body").asString()).toList()).containsExactlyInAnyOrderElementsOf(texts);
        assertThat(versions(all)).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void concurrentPushes_forDifferentUsers_keepSeparateCounters() throws Exception {
        List<Device> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            users.add(register("user" + i + "@example.com", "Phone"));
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (Device user : users) {
            tasks.add(() -> {
                for (int p = 0; p < 10; p++) {
                    assertThat(push(user, List.of(newNote(UUID.randomUUID(), "n", ""))).status()).isEqualTo(200);
                }
                return 1;
            });
        }

        together(tasks);

        for (int i = 0; i < 4; i++) {
            assertThat(counter("user" + i + "@example.com")).isEqualTo(10);
            assertThat(versions(pullAll(users.get(i), 0))).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        }
    }

    // ---- cross-account isolation (section 10.5) ------------------------------------------------

    @Test
    void userB_cannotReadOrOverwrite_userAsNotes_evenWithTheirIds() throws Exception {
        Device alice = register("alice@example.com", "Phone");
        Device bob = register("bob@example.com", "Phone");
        UUID id = UUID.randomUUID();
        push(alice, List.of(newNote(id, "alice's secret", "private")));

        assertThat(pull(bob, 0, 200).body().get("notes").size()).isZero();

        // Bob uses Alice's note id, even with a base that would win on her note: he gets his own note.
        JsonNode result = push(bob, List.of(change(id, bases(1, 1, 1, 1), Map.of("title", "overwritten"))))
                .body().get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("APPLIED");
        assertThat(result.get("note").get("body").asString()).isEmpty();      // nothing of hers leaked

        List<JsonNode> hers = pullAll(alice, 0);
        assertThat(hers).hasSize(1);
        assertThat(hers.getFirst().get("title").asString()).isEqualTo("alice's secret");
        assertThat(hers.getFirst().get("body").asString()).isEqualTo("private");
        assertThat(pullAll(bob, 0).getFirst().get("title").asString()).isEqualTo("overwritten");
        assertThat(counter("alice@example.com")).isEqualTo(1);
    }

    // ---- token refresh and revocation (conflict matrix C11, C12) --------------------------------

    @Test
    void anExpiredAccessToken_refreshes_andTheRetrySucceeds() throws Exception {
        Device device = register("you@example.com", "Phone");
        String userId = jdbc.sql("SELECT id::text FROM users").query(String.class).single();
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(userId).issuedAt(past.minus(15, ChronoUnit.MINUTES)).expiresAt(past).build()))
                .getTokenValue();
        Device stale = new Device(device.id(), device.name(), expired, device.refreshToken());
        List<Map<String, Object>> changes = List.of(newNote(UUID.randomUUID(), "typed offline", ""));

        Reply rejected = push(stale, changes);
        assertThat(rejected.status()).isEqualTo(401);
        assertThat(rejected.raw().headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                v -> assertThat(v).startsWith("Bearer"));
        assertThat(rejected.body().get("code").asString()).isEqualTo("TOKEN_INVALID");

        Reply refreshed = send("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", stale.refreshToken()));
        assertThat(refreshed.status()).isEqualTo(200);
        Device renewed = new Device(device.id(), device.name(), refreshed.body().get("accessToken").asString(),
                refreshed.body().get("refreshToken").asString());

        assertThat(push(renewed, changes).body().get("results").get(0).get("status").asString()).isEqualTo("APPLIED");
    }

    @Test
    void aRevokedSession_mustSignInAgain_andItsNotesThenSync() throws Exception {
        Device device = register("you@example.com", "Phone");
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now()").update();

        Reply refused = send("POST", "/api/v1/auth/refresh", null, Map.of("refreshToken", device.refreshToken()));
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body().get("code").asString()).isEqualTo("TOKEN_INVALID");

        Device again = signIn("login", "you@example.com", "Phone");
        assertThat(push(again, List.of(newNote(UUID.randomUUID(), "pending", ""))).status()).isEqualTo(200);
        assertThat(pullAll(again, 0)).hasSize(1);
    }

    // ---- things only real HTTP shows ------------------------------------------------------------

    /** The largest push section 8.4 allows: 50 notes of 100,000 emoji, about 20 MB of JSON. */
    @Test
    void theLargestAllowedPush_getsThroughAndComesBackIntact() throws Exception {
        Device phone = register("you@example.com", "Phone");
        String emoji = new String(Character.toChars(0x1F600));
        String body = emoji.repeat(100_000);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            changes.add(newNote(UUID.randomUUID(), emoji.repeat(300), body));
        }

        Reply r = push(phone, changes);

        assertThat(r.status()).isEqualTo(200);
        for (JsonNode result : r.body().get("results")) {
            assertThat(result.get("status").asString()).isEqualTo("APPLIED");
        }
        List<JsonNode> all = pullAll(phone, 0);
        assertThat(all).hasSize(50);
        assertThat(all.getFirst().get("body").asString()).isEqualTo(body);
    }

    /**
     * A push that fails part-way must leave nothing behind, and the client must see a 5xx: the app
     * retries a 5xx later, but treats a 401 as an expired session. A row already holding number 2
     * makes the second note collide after the first one was written.
     */
    @Test
    void aServerError_rollsBackTheWholePush_andIsA500_notA401() throws Exception {
        Device phone = register("you@example.com", "Phone");
        UUID blocker = UUID.randomUUID();
        UUID device = phone.id();
        jdbc.sql("""
                INSERT INTO notes (user_id, id, title, body, pinned, trashed,
                                   title_version, title_device, body_version, body_device,
                                   pinned_version, pinned_device, trashed_version, trashed_device,
                                   server_version, created_at, updated_at)
                SELECT id, :note, 'blocker', '', false, false, 2, :d, 2, :d, 2, :d, 2, :d, 2, now(), now() FROM users
                """).param("note", blocker).param("d", device).update();

        Reply r = push(phone, List.of(newNote(UUID.randomUUID(), "a", ""), newNote(UUID.randomUUID(), "b", "")));

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.raw().headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(r.body().get("status").asInt()).isEqualTo(500);
        assertThat(r.body().get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.raw().body()).doesNotContain("Exception", "notes_user_version_idx", "INSERT");   // no internals
        assertThat(jdbc.sql("SELECT id FROM notes").query(UUID.class).list()).containsExactly(blocker);
        assertThat(counter("you@example.com")).isZero();
    }

    @Test
    void anUnknownPath_isA404ForASignedInClient_andA401Otherwise() throws Exception {
        Device phone = register("you@example.com", "Phone");
        assertThat(send("GET", "/api/v1/nope", phone.accessToken(), null).status()).isEqualTo(404);
        assertThat(send("GET", "/api/v1/nope", null, null).status()).isEqualTo(401);
    }

    @Test
    void health_isPublic_andRevealsNoDetails() throws Exception {
        Reply r = send("GET", "/actuator/health", null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asString()).isEqualTo("UP");
        assertThat(r.body().has("components")).isFalse();
        assertThat(send("GET", "/actuator/env", null, null).status()).isEqualTo(401);
    }
}
