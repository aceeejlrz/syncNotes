package com.jezrel.syncnotes.auth;

import com.jezrel.syncnotes.auth.dto.AuthResponse;
import com.jezrel.syncnotes.auth.dto.CredentialsRequest;
import com.jezrel.syncnotes.common.InvalidRequestException;
import com.jezrel.syncnotes.common.TokenRejectedException;
import com.jezrel.syncnotes.user.UserRepository;
import com.jezrel.syncnotes.user.UserRow;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Register, login, refresh and logout (section 10). BCrypt is deliberately slow, so hashing happens
 * before any transaction starts and never holds a database connection.
 */
@Service
public class AuthService {

    static final int PASSWORD_MIN_CHARACTERS = 8;
    static final int PASSWORD_MAX_BYTES = 72;              // BCrypt's limit, in UTF-8 bytes

    private final UserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final String hashForUnknownEmail;

    public AuthService(UserRepository users, TokenService tokens, PasswordEncoder encoder, TransactionTemplate tx) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.tx = tx;
        this.hashForUnknownEmail = encoder.encode(UUID.randomUUID().toString());
    }

    public AuthResponse register(CredentialsRequest request) {
        checkNewPassword(request.password());
        String hash = encoder.encode(request.password());
        UUID userId = UUID.randomUUID();
        String refreshToken = tx.execute(status -> {
            if (!users.create(userId, request.email(), hash)) {
                throw new EmailTakenException();
            }
            return tokens.newRefreshToken(userId, request.deviceId());
        });
        return response(userId, request.email(), refreshToken);
    }

    public AuthResponse login(CredentialsRequest request) {
        Optional<UserRow> user = users.findByEmail(request.email());
        // Check a hash even for an unknown email, so timing doesn't reveal which emails have accounts.
        boolean matches = encoder.matches(request.password(), user.map(UserRow::passwordHash).orElse(hashForUnknownEmail));
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        return response(user.get().id(), user.get().email(), tokens.newRefreshToken(user.get().id(), request.deviceId()));
    }

    public AuthResponse refresh(String refreshToken) {
        TokenService.Rotated rotated = tokens.rotate(refreshToken);
        UserRow user = users.findById(rotated.userId())
                .orElseThrow(() -> new TokenRejectedException("Account no longer exists"));
        return response(user.id(), user.email(), rotated.refreshToken());
    }

    public void logout(String refreshToken) {
        tokens.revoke(refreshToken);
    }

    private AuthResponse response(UUID userId, String email, String refreshToken) {
        return new AuthResponse(userId, email, tokens.newAccessToken(userId),
                tokens.accessTokenExpiresInSeconds(), refreshToken);
    }

    private static void checkNewPassword(String password) {
        if (password.codePointCount(0, password.length()) < PASSWORD_MIN_CHARACTERS) {
            throw new InvalidRequestException("Password must be at least " + PASSWORD_MIN_CHARACTERS + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new InvalidRequestException("Password is too long");
        }
    }
}
