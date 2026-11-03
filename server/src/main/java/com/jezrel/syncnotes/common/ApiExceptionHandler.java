package com.jezrel.syncnotes.common;

import com.jezrel.syncnotes.auth.EmailTakenException;
import com.jezrel.syncnotes.auth.InvalidCredentialsException;
import com.jezrel.syncnotes.user.UnknownUserException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Problem Details for every error (section 8.3). Spring's own 400s (bean validation, malformed
 * JSON, an invalid UUID, a non-numeric query parameter) get {@code code: VALIDATION_FAILED} too.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return ApiProblems.of(HttpStatus.BAD_REQUEST, ApiProblems.VALIDATION_FAILED, e.getMessage());
    }

    @ExceptionHandler(EmailTakenException.class)
    ProblemDetail emailTaken() {
        return ApiProblems.of(HttpStatus.CONFLICT, ApiProblems.EMAIL_TAKEN, "An account with this email already exists.");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ProblemDetail> invalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(ApiProblems.of(HttpStatus.UNAUTHORIZED, ApiProblems.INVALID_CREDENTIALS,
                        "Invalid email or password."));
    }

    /** A signed token that can't be used is treated like a bad signature: sign in again. */
    @ExceptionHandler({TokenRejectedException.class, UnknownUserException.class})
    ResponseEntity<ProblemDetail> tokenRejected(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .body(ApiProblems.of(HttpStatus.UNAUTHORIZED, ApiProblems.TOKEN_INVALID,
                        "Missing, invalid or expired access token."));
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(ApiProblems.of(HttpStatus.TOO_MANY_REQUESTS, ApiProblems.RATE_LIMITED,
                        "Too many attempts. Try again shortly."));
    }

    /** Anything unexpected: the details go to the log, never to the client. The app retries a 5xx. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        logger.error("Unhandled exception", e);
        return ApiProblems.of(HttpStatus.INTERNAL_SERVER_ERROR, ApiProblems.INTERNAL_ERROR,
                "Something went wrong on the server. Try again later.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        // Some handlers pass a null body and super builds the ProblemDetail, so tag the result.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()
                && response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", ApiProblems.VALIDATION_FAILED);
        }
        return response;
    }
}
