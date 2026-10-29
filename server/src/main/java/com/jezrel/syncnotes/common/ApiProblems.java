package com.jezrel.syncnotes.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** The machine-readable {@code code} values of section 8.3 and helpers to attach them. */
public final class ApiProblems {
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";

    private ApiProblems() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    /**
     * Writes a problem from a servlet filter, where no message converters are available. The
     * values are constants from this server, never request input, so plain formatting is safe.
     */
    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\"}"
                        .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }
}
