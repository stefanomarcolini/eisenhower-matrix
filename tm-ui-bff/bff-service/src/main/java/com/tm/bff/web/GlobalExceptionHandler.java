package com.tm.bff.web;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * BFF global exception handler — ensures every error response is a JSON ProblemDetail
 * with a {@code title} field that the React frontend can display.
 *
 * Without this handler, Spring Boot's default {@code BasicErrorController} returns
 * {@code {error, status, timestamp}} (no {@code title}), causing the frontend to fall
 * through to hardcoded fallback messages.
 *
 * When the BFF wraps a Core API error as a {@link ResponseStatusException}, the reason
 * string is the raw JSON body from Core API (a ProblemDetail). This handler parses that
 * JSON and forwards the Core API's {@code title} and {@code detail} directly to the client.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * Forward Core API errors (wrapped as ResponseStatusException by controllers).
     * The {@code reason} may be a JSON string from Core API — extract its title/detail
     * so the browser receives a clean, readable ProblemDetail.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatusCode());
        String reason = ex.getReason();

        if (reason != null) {
            try {
                Map<String, Object> coreBody = objectMapper.readValue(
                        reason, new TypeReference<Map<String, Object>>() {});
                String title  = (String) coreBody.get("title");
                String detail = (String) coreBody.get("detail");
                if (title  != null) pd.setTitle(title);
                if (detail != null) pd.setDetail(detail);
            } catch (Exception ignored) {
                // reason is not JSON — use it directly as the title
                pd.setTitle(reason);
            }
        }

        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    /**
     * Missing static resources should be a normal 404 (e.g. /favicon.ico before an icon is added),
     * not an internal server error.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not Found");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    /**
     * Catch-all for unhandled exceptions (e.g. RestClient connection failures when
     * Core API is unreachable). Returns a generic 500 ProblemDetail so the frontend
     * always receives JSON rather than Tomcat's HTML error page.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Service Unavailable");
        pd.setDetail("Unable to process the request. Please try again later.");
        return ResponseEntity.internalServerError().body(pd);
    }
}
