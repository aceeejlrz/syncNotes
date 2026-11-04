package com.jezrel.syncnotes.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jezrel.syncnotes.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Accounts and tokens over HTTP against real PostgreSQL 17 (sections 8.2, 8.3, 10). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerTest {

    static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String PASSWORD = "correct horse";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtDecoder decoder;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Builds JSON safely for awkward passwords: \\uXXXX escapes survive any characters. */
    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.chars().forEach(c -> out.append("\\u%04x".formatted(c)));
        return out.append('"').toString();
    }

    static String credentials(String email, String password) {
        return """
                {"email":%s,"password":%s,"deviceId":"%s"}""".formatted(json(email), json(password), DEVICE);
    }

    static String refreshBody(String token) {
        return """
                {"refreshToken":"%s"}""".formatted(token);
    }

    ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions register(String email, String password) throws Exception {
        return postJson("register", credentials(email, password));
    }

    ResultActions login(String email, String password) throws Exception {
        return postJson("login", credentials(email, password));
    }

    String field(ResultActions result, String name) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$." + name);
    }

    String registered(String email) throws Exception {
        return field(register(email, PASSWORD).andExpect(status().isCreated()), "refreshToken");
    }

    ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken));
    }

    void expectValidationFailed(ResultActions r) throws Exception {
        r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    long tokenCount() {
        return jdbc.sql("SELECT count(*) FROM refresh_tokens").query(Long.class).single();
    }

    long liveTokens() {
        return jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL").query(Long.class).single();
    }

    // ---- register -----------------------------------------------------------------------------

    @Test
    void register_returnsTheSection83Shape_andTheTokensWork() throws Exception {
        ResultActions r = register("you@example.com", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isString())
                .andExpect(jsonPath("$.email").value("you@example.com"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").isString());

        String userId = field(r, "userId");
        me(field(r, "accessToken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value("you@example.com"));
    }

    @Test
    void accessToken_isHs256_withTheUserAsSubject_andA15MinuteLifetime() throws Exception {
        ResultActions r = register("you@example.com", PASSWORD);
        Jwt jwt = decoder.decode(field(r, "accessToken"));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getSubject()).isEqualTo(field(r, "userId"));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void secrets_areOnlyStoredHashed() throws Exception {
        String refreshToken = registered("you@example.com");

        String passwordHash = jdbc.sql("SELECT password_hash FROM users").query(String.class).single();
        assertThat(passwordHash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        String tokenHash = jdbc.sql("SELECT token_hash FROM refresh_tokens").query(String.class).single();
        assertThat(tokenHash).hasSize(64).isHexadecimal().isNotEqualTo(refreshToken);
        assertThat(java.util.Base64.getUrlDecoder().decode(refreshToken)).hasSize(32);
    }

    @Test
    void email_isTrimmedAndLowercased() throws Exception {
        register("  You@Example.COM ", PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("you@example.com"));
        login("YOU@example.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aTakenEmail_is409_inAnyCase_andKeepsTheOriginalPassword() throws Exception {
        registered("you@example.com");
        register("YOU@example.com", "another password")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        login("you@example.com", PASSWORD).andExpect(status().isOk());
        assertThat(tokenCount()).isEqualTo(2);                               // register + login, none for the 409
    }

    @Test
    void twoConcurrentRegistrations_oneWins_theOtherIs409() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return register("you@example.com", PASSWORD).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> call : calls) {
            statuses.add(call.get(30, TimeUnit.SECONDS));
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void passwordRules_atLeast8Characters_atMost72Bytes() throws Exception {
        String emoji = new String(Character.toChars(0x1F600));                // 4 UTF-8 bytes, 2 UTF-16 units
        expectValidationFailed(register("a@example.com", "1234567"));
        expectValidationFailed(register("b@example.com", emoji.repeat(7)));  // 14 UTF-16 units, 7 characters
        expectValidationFailed(register("c@example.com", "x".repeat(73)));
        expectValidationFailed(register("d@example.com", emoji.repeat(19))); // 76 bytes
        register("e@example.com", "12345678").andExpect(status().isCreated());
        register("f@example.com", "x".repeat(72)).andExpect(status().isCreated());
        register("g@example.com", emoji.repeat(18)).andExpect(status().isCreated());
    }

    @Test
    void badRequestShapes_are400() throws Exception {
        expectValidationFailed(register("not-an-email", PASSWORD));
        expectValidationFailed(register("nul\u0000@example.com", PASSWORD));
        expectValidationFailed(postJson("register", "{\"email\":\"a@example.com\",\"password\":\"12345678\"}"));
        expectValidationFailed(postJson("register", "{\"email\":\"a@example.com\",\"deviceId\":\"" + DEVICE + "\"}"));
        expectValidationFailed(postJson("register",
                "{\"email\":\"a@example.com\",\"password\":\"12345678\",\"deviceId\":\"nope\"}"));
        expectValidationFailed(postJson("login", "{"));
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Long.class).single()).isZero();
    }

    // ---- login --------------------------------------------------------------------------------

    @Test
    void login_returnsTokens() throws Exception {
        registered("you@example.com");
        ResultActions r = login("you@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("you@example.com"))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900));
        me(field(r, "accessToken")).andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordAndAnUnknownEmail_getTheSameAnswer() throws Exception {
        registered("you@example.com");
        String wrong = login("you@example.com", "wrong password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknown = login("nobody@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(wrong);
    }

    /** The new-password rules don't apply at login; odd input is just a wrong password, never a 500. */
    @Test
    void shortAndVeryLongPasswords_atLogin_are401() throws Exception {
        registered("you@example.com");
        login("you@example.com", "x").andExpect(status().isUnauthorized());
        login("you@example.com", "").andExpect(status().isUnauthorized());
        login("you@example.com", "x".repeat(10_000)).andExpect(status().isUnauthorized());
    }

    // ---- refresh ------------------------------------------------------------------------------

    @Test
    void refresh_returnsANewPair_andOnlyTheNewTokenStaysLive() throws Exception {
        String first = registered("you@example.com");

        ResultActions r = postJson("refresh", refreshBody(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("you@example.com"));
        String second = field(r, "refreshToken");
        assertThat(second).isNotEqualTo(first);
        me(field(r, "accessToken")).andExpect(status().isOk());

        assertThat(liveTokens()).isEqualTo(1);
        postJson("refresh", refreshBody(second)).andExpect(status().isOk());   // the new one works
    }

    // ---- grace period for a lost response (section 10.2) -----------------------------------------

    /** The response to a refresh is lost: the phone only has the old token, and retries with it. */
    @Test
    void aLostResponse_canBeRetriedWithTheOldToken_andTheLostTokenStopsWorking() throws Exception {
        String old = registered("you@example.com");
        String lost = field(postJson("refresh", refreshBody(old)).andExpect(status().isOk()), "refreshToken");

        ResultActions retry = postJson("refresh", refreshBody(old)).andExpect(status().isOk());
        String current = field(retry, "refreshToken");
        me(field(retry, "accessToken")).andExpect(status().isOk());

        assertThat(current).isNotEqualTo(lost);
        assertThat(liveTokens()).isEqualTo(1);
        postJson("refresh", refreshBody(lost))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        postJson("refresh", refreshBody(current)).andExpect(status().isOk());
    }

    /** The replacement was used, so the phone did get it. The old token is now suspicious. */
    @Test
    void theOldToken_isRefused_onceItsReplacementWasUsed() throws Exception {
        String first = registered("you@example.com");
        String second = field(postJson("refresh", refreshBody(first)), "refreshToken");
        String third = field(postJson("refresh", refreshBody(second)).andExpect(status().isOk()), "refreshToken");

        postJson("refresh", refreshBody(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        postJson("refresh", refreshBody(third)).andExpect(status().isOk());    // and it harms nothing
    }

    @Test
    void afterTheGracePeriod_theOldTokenIsRefused_andTheReplacementStillWorks() throws Exception {
        String old = registered("you@example.com");
        String replacement = field(postJson("refresh", refreshBody(old)), "refreshToken");
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() - interval '31 seconds' WHERE replaced_by IS NOT NULL")
                .update();

        postJson("refresh", refreshBody(old)).andExpect(status().isUnauthorized());
        postJson("refresh", refreshBody(replacement)).andExpect(status().isOk());
    }

    @Test
    void justInsideTheGracePeriod_theRetryIsAccepted() throws Exception {
        String old = registered("you@example.com");
        postJson("refresh", refreshBody(old));
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() - interval '25 seconds' WHERE replaced_by IS NOT NULL")
                .update();

        postJson("refresh", refreshBody(old)).andExpect(status().isOk());
    }

    @Test
    void aRetry_doesNotExtendTheGracePeriod() throws Exception {
        String old = registered("you@example.com");
        postJson("refresh", refreshBody(old));
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() - interval '20 seconds' WHERE replaced_by IS NOT NULL")
                .update();

        postJson("refresh", refreshBody(old)).andExpect(status().isOk());

        Double age = jdbc.sql("""
                SELECT extract(epoch FROM now() - revoked_at)::float8 FROM refresh_tokens
                WHERE created_at = (SELECT min(created_at) FROM refresh_tokens)
                """).query(Double.class).single();
        assertThat(age).isGreaterThan(19);
    }

    /** Only a rotation gives a grace period: signing out is final, at once. */
    @Test
    void aLoggedOutToken_getsNoGracePeriod() throws Exception {
        String token = registered("you@example.com");
        postJson("logout", refreshBody(token)).andExpect(status().isNoContent());
        postJson("refresh", refreshBody(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    /** Signing out while holding only the old token must also end the replacement that got lost. */
    @Test
    void loggingOutWithTheOldToken_endsTheLostReplacementToo() throws Exception {
        String old = registered("you@example.com");
        String lost = field(postJson("refresh", refreshBody(old)), "refreshToken");

        postJson("logout", refreshBody(old)).andExpect(status().isNoContent());

        assertThat(liveTokens()).isZero();
        postJson("refresh", refreshBody(lost)).andExpect(status().isUnauthorized());
        postJson("refresh", refreshBody(old)).andExpect(status().isUnauthorized());
    }

    /** Logging out with the replacement ends the chain: the old token can't bring it back. */
    @Test
    void afterLoggingOutWithTheReplacement_theOldTokenIsRefused() throws Exception {
        String old = registered("you@example.com");
        String replacement = field(postJson("refresh", refreshBody(old)), "refreshToken");

        postJson("logout", refreshBody(replacement)).andExpect(status().isNoContent());

        postJson("refresh", refreshBody(old)).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_keepsTheDeviceOfTheOldToken() throws Exception {
        String first = registered("you@example.com");
        postJson("refresh", refreshBody(first)).andExpect(status().isOk());
        List<UUID> devices = jdbc.sql("SELECT device_id FROM refresh_tokens").query(UUID.class).list();
        assertThat(devices).hasSize(2).containsOnly(UUID.fromString(DEVICE));
    }

    @Test
    void anUnknownExpiredOrRevokedToken_is401() throws Exception {
        postJson("refresh", refreshBody("never-issued"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        String expired = registered("expired@example.com");
        jdbc.sql("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'").update();
        postJson("refresh", refreshBody(expired)).andExpect(status().isUnauthorized());

        String revoked = registered("revoked@example.com");                    // conflict matrix C12
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now()").update();
        postJson("refresh", refreshBody(revoked)).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_clearsThatUsersExpiredTokens_butKeepsRevokedOnesUntilTheyExpire() throws Exception {
        String old = registered("you@example.com");
        postJson("refresh", refreshBody(old)).andExpect(status().isOk());      // old: revoked, not expired
        String other = registered("other@example.com");
        String current = field(login("you@example.com", PASSWORD), "refreshToken");
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, device_id, expires_at)
                SELECT :id, id, 'long-expired', :device, now() - interval '1 day'
                FROM users WHERE email = 'you@example.com'
                """).param("id", UUID.randomUUID()).param("device", UUID.fromString(DEVICE)).update();
        assertThat(tokenCount()).isEqualTo(5);

        postJson("refresh", refreshBody(current)).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE token_hash = 'long-expired'")
                .query(Long.class).single()).isZero();
        assertThat(tokenCount()).isEqualTo(5);                                 // -1 expired, +1 replacement
        postJson("refresh", refreshBody(other)).andExpect(status().isOk());    // another user's are untouched
    }

    /** The same token at once: they run one at a time, and only the last one's token stays live. */
    @Test
    void concurrentRefreshes_ofOneToken_leaveExactlyOneLiveToken() throws Exception {
        String token = registered("you@example.com");
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return postJson("refresh", refreshBody(token)).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> call : calls) {
            statuses.add(call.get(30, TimeUnit.SECONDS));
        }
        assertThat(statuses).containsOnly(200);                                // all inside the grace period
        assertThat(tokenCount()).isEqualTo(9);
        assertThat(liveTokens()).isEqualTo(1);
    }

    @Test
    void aMissingRefreshToken_is400() throws Exception {
        expectValidationFailed(postJson("refresh", "{}"));
        expectValidationFailed(postJson("logout", "{\"refreshToken\":\"\"}"));
    }

    // ---- logout -------------------------------------------------------------------------------

    @Test
    void logout_revokesTheToken_andIsSafeToRepeat() throws Exception {
        String token = registered("you@example.com");
        postJson("logout", refreshBody(token)).andExpect(status().isNoContent());
        postJson("refresh", refreshBody(token)).andExpect(status().isUnauthorized());
        postJson("logout", refreshBody(token)).andExpect(status().isNoContent());
        postJson("logout", refreshBody("never-issued")).andExpect(status().isNoContent());
    }

    @Test
    void logout_onlyEndsThatSession() throws Exception {
        String phone = registered("you@example.com");
        String tablet = field(login("you@example.com", PASSWORD), "refreshToken");
        postJson("logout", refreshBody(phone)).andExpect(status().isNoContent());
        postJson("refresh", refreshBody(tablet)).andExpect(status().isOk());
    }

    // ---- /me ----------------------------------------------------------------------------------

    @Test
    void me_needsAToken() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void me_forADeletedAccount_is401_andItsRefreshTokensAreGone() throws Exception {
        ResultActions r = register("you@example.com", PASSWORD);
        String access = field(r, "accessToken");
        String refresh = field(r, "refreshToken");
        jdbc.sql("DELETE FROM users").update();

        me(access).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        postJson("refresh", refreshBody(refresh)).andExpect(status().isUnauthorized());
    }

    // ---- the tokens work where they are needed -------------------------------------------------

    @Test
    void anIssuedToken_canPushAndPull_asItsOwnAccount() throws Exception {
        String access = field(register("you@example.com", PASSWORD), "accessToken");
        String otherAccess = field(register("other@example.com", PASSWORD), "accessToken");
        String push = """
                {"deviceId":"%s","deviceName":"Pixel 8","changes":[
                 {"id":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "baseVersions":{"title":0,"body":0,"pinned":0,"trashed":0},
                  "createdAt":"2026-10-06T09:12:40Z","title":"Trip ideas"}]}""".formatted(DEVICE);

        mvc.perform(post("/api/v1/sync/push").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(push))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"));

        mvc.perform(get("/api/v1/sync/pull").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.notes[0].title").value("Trip ideas"));
        mvc.perform(get("/api/v1/sync/pull").header("Authorization", "Bearer " + otherAccess))
                .andExpect(jsonPath("$.notes.length()").value(0));
    }
}
