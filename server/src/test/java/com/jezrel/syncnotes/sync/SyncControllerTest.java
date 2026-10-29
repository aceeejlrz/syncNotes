package com.jezrel.syncnotes.sync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jezrel.syncnotes.TestcontainersConfiguration;
import com.jezrel.syncnotes.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The sync endpoints over HTTP: authentication with real HS256 tokens (through the real
 * JwtDecoder), the JSON shapes of section 8.3, and the 400s of section 8.4.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SyncControllerTest {

    static final UUID ALICE = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    static final UUID BOB = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    static final String PHONE = "0f8fad5b-d9cb-469f-a165-70867728950e";
    static final String NOTE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired UserRepository users;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("DELETE FROM refresh_tokens").update();
        jdbc.sql("DELETE FROM notes").update();
        jdbc.sql("DELETE FROM users").update();
        users.create(ALICE, "alice@example.com", "hash");
        users.create(BOB, "bob@example.com", "hash");
    }

    // ---- tokens ------------------------------------------------------------------------------

    static JwtClaimsSet claims(String subject, Instant expiresAt) {
        return JwtClaimsSet.builder().subject(subject).issuedAt(expiresAt.minus(15, ChronoUnit.MINUTES))
                .expiresAt(expiresAt).build();
    }

    /** Signed with the server's key, HS256 header as section 10.4 requires. */
    String token(String subject, Instant expiresAt) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(subject, expiresAt))).getTokenValue();
    }

    String token(UUID user) {
        return token(user.toString(), Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- requests ----------------------------------------------------------------------------

    static String newNote(String id, String title) {
        return """
                {"id":"%s","baseVersions":{"title":0,"body":0,"pinned":0,"trashed":0},
                 "createdAt":"2026-10-06T09:12:40Z","title":"%s","body":"","pinned":false,"trashed":false}"""
                .formatted(id, title);
    }

    static String pushBody(String... changes) {
        return """
                {"deviceId":"%s","deviceName":"Pixel 8","changes":[%s]}""".formatted(PHONE, String.join(",", changes));
    }

    ResultActions push(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/sync/push").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions expectValidationFailed(ResultActions r) throws Exception {
        return r.andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- authentication ----------------------------------------------------------------------

    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void noToken_is401_withProblemDetailsAndWwwAuthenticate() throws Exception {
        mvc.perform(post("/api/v1/sync/push").contentType(MediaType.APPLICATION_JSON).content(pushBody(newNote(NOTE, "x"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        mvc.perform(get("/api/v1/sync/pull")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithTheServersKey_isAccepted() throws Exception {
        mvc.perform(get("/api/v1/sync/pull").header("Authorization", bearer(token(ALICE))))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenSignedWithAnotherKey_is401() throws Exception {
        byte[] otherKey = new byte[32];
        new java.security.SecureRandom().nextBytes(otherKey);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        String forged = forger.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(ALICE.toString(), Instant.now().plus(15, ChronoUnit.MINUTES)))).getTokenValue();

        mvc.perform(get("/api/v1/sync/pull").header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void anExpiredToken_is401() throws Exception {
        String expired = token(ALICE.toString(), Instant.now().minus(5, ChronoUnit.MINUTES)); // past the 60 s skew
        mvc.perform(get("/api/v1/sync/pull").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void aSubjectThatIsNotAUserId_is401() throws Exception {
        String odd = token("not-a-uuid", Instant.now().plus(15, ChronoUnit.MINUTES));
        mvc.perform(get("/api/v1/sync/pull").header("Authorization", bearer(odd)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void aValidTokenForADeletedAccount_is401_notA500() throws Exception {
        push(token(UUID.randomUUID()), pushBody(newNote(NOTE, "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    // ---- shapes ------------------------------------------------------------------------------

    @Test
    void push_returnsTheSection83Shape() throws Exception {
        push(token(ALICE), pushBody(newNote(NOTE, "Trip ideas")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].id").value(NOTE))
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.results[0].error").doesNotExist())
                .andExpect(jsonPath("$.results[0].note.title").value("Trip ideas"))
                .andExpect(jsonPath("$.results[0].note.createdAt").value("2026-10-06T09:12:40Z"))
                .andExpect(jsonPath("$.results[0].note.updatedAt").isString())
                .andExpect(jsonPath("$.results[0].note.fieldVersions.title").value(1))
                .andExpect(jsonPath("$.results[0].note.fieldVersions.trashed").value(1))
                .andExpect(jsonPath("$.results[0].note.version").value(1));
    }

    @Test
    void aRejectedNote_hasAnErrorAndNoNote() throws Exception {
        push(token(ALICE), pushBody(newNote(NOTE, "x".repeat(301))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].error").isString())
                .andExpect(jsonPath("$.results[0].note").doesNotExist());
    }

    @Test
    void pull_returnsNotesCursorAndHasMore_andIsScopedToTheTokensAccount() throws Exception {
        push(token(ALICE), pushBody(newNote(NOTE, "alice's"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/sync/pull?since=0&limit=200").header("Authorization", bearer(token(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes.length()").value(1))
                .andExpect(jsonPath("$.notes[0].title").value("alice's"))
                .andExpect(jsonPath("$.nextCursor").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/sync/pull").header("Authorization", bearer(token(BOB))))   // defaults
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(0));
    }

    // ---- 400 VALIDATION_FAILED (section 8.4) ---------------------------------------------------

    @Test
    void noChanges_or51_are400() throws Exception {
        expectValidationFailed(push(token(ALICE), pushBody()));
        String fiftyOne = IntStream.range(0, 51).mapToObj(i -> newNote(UUID.randomUUID().toString(), "n" + i))
                .collect(Collectors.joining(","));
        expectValidationFailed(push(token(ALICE), pushBody(fiftyOne)));
    }

    @Test
    void duplicateIds_are400() throws Exception {
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "one"), newNote(NOTE, "two"))));
    }

    @Test
    void anInvalidUuid_is400() throws Exception {
        expectValidationFailed(push(token(ALICE), pushBody(newNote("not-a-uuid", "x"))));
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x")).replace(PHONE, "nope")));
    }

    @Test
    void aMissingOrNegativeBase_is400() throws Exception {
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x").replace("\"trashed\":0}", "}")
                .replace(",}", "}"))));
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x").replace("\"title\":0", "\"title\":-1"))));
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x")
                .replace("\"baseVersions\":{\"title\":0,\"body\":0,\"pinned\":0,\"trashed\":0},", ""))));
    }

    @Test
    void aMissingCreatedAtOrDeviceId_is400() throws Exception {
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x").replace("\"createdAt\":\"2026-10-06T09:12:40Z\",", ""))));
        expectValidationFailed(push(token(ALICE), pushBody(newNote(NOTE, "x")).replace("\"deviceId\":\"" + PHONE + "\",", "")));
    }

    @Test
    void malformedJson_is400() throws Exception {
        expectValidationFailed(push(token(ALICE), "{\"deviceId\":"));
    }

    @Test
    void aNonNumericOrNegativeCursor_is400() throws Exception {
        expectValidationFailed(mvc.perform(get("/api/v1/sync/pull?since=abc").header("Authorization", bearer(token(ALICE)))));
        expectValidationFailed(mvc.perform(get("/api/v1/sync/pull?since=-1").header("Authorization", bearer(token(ALICE)))));
    }
}
