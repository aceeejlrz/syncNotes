package com.jezrel.syncnotes.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The signing secret must be real base64 of at least 256 bits, or the server won't start. */
class JwtConfigTest {

    static AuthProperties secret(String value) {
        return new AuthProperties(value, Duration.ofMinutes(15), Duration.ofDays(30));
    }

    @Test
    void a32ByteSecret_makesAnHmacSha256Key() {
        String b64 = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(JwtConfig.key(secret(b64)).getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(JwtConfig.key(secret(b64)).getEncoded()).hasSize(32);
    }

    @Test
    void anUnsetSecret_saysSo() {
        assertThatThrownBy(() -> JwtConfig.key(secret("${JWT_SECRET}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not set");
    }

    @Test
    void aShortSecret_isRefused() {
        String b64 = Base64.getEncoder().encodeToString(new byte[31]);
        assertThatThrownBy(() -> JwtConfig.key(secret(b64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void aSecretThatIsNotBase64_isRefused() {
        assertThatThrownBy(() -> JwtConfig.key(secret("this is a plain passphrase, not base64!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }
}
