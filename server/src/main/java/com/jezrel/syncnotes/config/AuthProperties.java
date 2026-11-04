package com.jezrel.syncnotes.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code syncnotes.auth.*} (section 10). {@code jwtSecret} is the base64 of at least 32 random
 * bytes ({@code openssl rand -base64 32}); in production it comes from the JWT_SECRET secret.
 */
@Validated
@ConfigurationProperties("syncnotes.auth")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue("10") int rateLimitPerMinute,
        @DefaultValue("30s") Duration refreshTokenGrace) {
}
