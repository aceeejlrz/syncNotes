package com.jezrel.syncnotes.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HS256 access tokens with one 256-bit secret (section 10.4). When encoding, pass
 * {@code JwsHeader.with(MacAlgorithm.HS256)}: the default header is RS256, which fails with a
 * secret key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    JwtDecoder jwtDecoder(AuthProperties props) {
        return NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(AuthProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
    }

    static SecretKey key(AuthProperties props) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(props.jwtSecret().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("syncnotes.auth.jwt-secret (JWT_SECRET) must be base64", e);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("syncnotes.auth.jwt-secret (JWT_SECRET) must decode to at least "
                    + MIN_SECRET_BYTES + " bytes; generate one with: openssl rand -base64 32");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
