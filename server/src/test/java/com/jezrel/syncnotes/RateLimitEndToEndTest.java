package com.jezrel.syncnotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 2.9 over real HTTP, with the production limit of 10 per minute. Each test uses its own client
 * address, because buckets outlive a test. A token refills every 6 s, so each test spends its bucket
 * immediately before checking the refusal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "syncnotes.auth.rate-limit-per-minute=10")
@Import(TestcontainersConfiguration.class)
class RateLimitEndToEndTest {

    static final int LIMIT = 10;
    static final String DEVICE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    static String newAddress() {
        return "203.0.113." + NEXT_ADDRESS.getAndIncrement();
    }

    static String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s","deviceId":"%s"}""".formatted(email, password, DEVICE);
    }

    static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    HttpResponse<String> post(String path, String address, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (address != null) {
            request.header("Fly-Client-IP", address);
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    void spend(String endpoint, String address) throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(post("/api/v1/auth/" + endpoint, address, "{").statusCode()).isNotEqualTo(429);
        }
    }

    String refreshTokenFor(String email, String address) throws Exception {
        HttpResponse<String> r = post("/api/v1/auth/register", address, credentials(email, "correct horse"));
        assertThat(r.statusCode()).isEqualTo(201);
        return JSON.readTree(r.body()).get("refreshToken").asString();
    }

    @Test
    void theEleventhLogin_is429_withProblemDetailsAndRetryAfter() throws Exception {
        String address = newAddress();
        String email = uniqueEmail();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(post("/api/v1/auth/login", address, credentials(email, "wrong password")).statusCode())
                    .isEqualTo(401);
        }

        HttpResponse<String> r = post("/api/v1/auth/login", address, credentials(email, "wrong password"));

        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(r.headers().firstValueAsLong("Retry-After").orElse(0)).isBetween(1L, 6L);
        JsonNode problem = JSON.readTree(r.body());
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.get("code").asString()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void register_isLimited_beforeAnAccountIsCreated() throws Exception {
        String address = newAddress();
        spend("register", address);
        String email = uniqueEmail();

        assertThat(post("/api/v1/auth/register", address, credentials(email, "correct horse")).statusCode())
                .isEqualTo(429);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :e").param("e", email)
                .query(Long.class).single()).isZero();
    }

    /** A refused refresh must not use up the token, or a 429 would end the session for good. */
    @Test
    void aRefusedRefresh_doesNotSpendTheToken() throws Exception {
        String token = refreshTokenFor(uniqueEmail(), newAddress());
        String body = "{\"refreshToken\":\"" + token + "\"}";
        String address = newAddress();
        spend("refresh", address);

        assertThat(post("/api/v1/auth/refresh", address, body).statusCode()).isEqualTo(429);

        assertThat(post("/api/v1/auth/refresh", newAddress(), body).statusCode()).isEqualTo(200);
    }

    @Test
    void anotherClient_andAnotherEndpoint_areNotAffected() throws Exception {
        String address = newAddress();
        spend("login", address);
        assertThat(post("/api/v1/auth/login", address, "{").statusCode()).isEqualTo(429);

        assertThat(post("/api/v1/auth/login", newAddress(), "{").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/auth/refresh", address, "{").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/auth/register", address, "{").statusCode()).isEqualTo(400);
    }

    @Test
    void logout_isNeverLimited() throws Exception {
        String address = newAddress();
        for (int i = 0; i < LIMIT * 2; i++) {
            assertThat(post("/api/v1/auth/logout", address, "{\"refreshToken\":\"x\"}").statusCode()).isEqualTo(204);
        }
    }

    @Test
    void syncIsNotLimited() throws Exception {
        String address = newAddress();
        HttpResponse<String> reg = post("/api/v1/auth/register", address, credentials(uniqueEmail(), "correct horse"));
        String access = JSON.readTree(reg.body()).get("accessToken").asString();
        for (int i = 0; i < LIMIT * 2; i++) {
            HttpRequest pull = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sync/pull"))
                    .header("Authorization", "Bearer " + access).header("Fly-Client-IP", address).GET().build();
            assertThat(HTTP.send(pull, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void varyingXForwardedFor_doesNotBypassTheLimit() throws Exception {
        String address = newAddress();
        spend("login", address);
        assertThat(post("/api/v1/auth/login", address, "{", "X-Forwarded-For", "198.51.100.1").statusCode())
                .isEqualTo(429);
    }

    /** Without the Fly header (no proxy) the connection's address counts, whatever X-Forwarded-For says. */
    @Test
    void withoutTheFlyHeader_varyingXForwardedFor_stillCountsAsOneClient() throws Exception {
        boolean refused = false;
        for (int i = 0; i < LIMIT + 2 && !refused; i++) {
            refused = post("/api/v1/auth/refresh", null, "{", "X-Forwarded-For", "198.51.100." + i).statusCode() == 429;
        }
        assertThat(refused).isTrue();
    }

    /** An encoded path reaches the same handler, so it must share the same bucket. */
    @Test
    void anEncodedPath_doesNotGetAFreshBucket() throws Exception {
        String address = newAddress();
        spend("login", address);
        assertThat(post("/api/v1/auth/logi%6E", address, "{").statusCode()).isEqualTo(429);
    }

    /** The opposite order: requests by encoded path use up the real endpoint's bucket. */
    @Test
    void anEncodedPath_isCountedAgainstTheEndpoint() throws Exception {
        String address = newAddress();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(post("/api/v1/auth/logi%6E", address, "{").statusCode()).isEqualTo(400);
        }
        assertThat(post("/api/v1/auth/login", address, "{").statusCode()).isEqualTo(429);
    }
}
