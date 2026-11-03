package com.jezrel.syncnotes.config;

import com.jezrel.syncnotes.common.ApiProblems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless bearer-token API (section 10.4). No cookies are used, so CSRF protection is off. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint tokenInvalid = tokenInvalidEntryPoint();
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(tokenInvalid))
                .exceptionHandling(e -> e.authenticationEntryPoint(tokenInvalid))
                .build();
    }

    /** BCrypt today; stored hashes name their algorithm, so it can change later (section 10.1). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 401 with the standard WWW-Authenticate header plus a Problem Details body (section 8.3). */
    private static AuthenticationEntryPoint tokenInvalidEntryPoint() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            bearer.commence(request, response, ex);
            ApiProblems.write(response, HttpStatus.UNAUTHORIZED, ApiProblems.TOKEN_INVALID,
                    "Missing, invalid or expired access token.");
        };
    }
}
