package com.tm.core.web;

import com.tm.core.domain.ConflictException;
import com.tm.core.domain.IllegalStateTransitionException;
import com.tm.core.domain.InvalidTokenException;
import com.tm.core.domain.PasswordPolicyViolationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * RFC 7807 error responses. Strips stack traces and implementation details from all responses.
 * See CODING_PATTERNS.md §16 and API_CONTRACT.md §Error Format.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBeanValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Validation Failed");
        pd.setDetail(detail);
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(PasswordPolicyViolationException.class)
    public ResponseEntity<ProblemDetail> handlePasswordPolicy(PasswordPolicyViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Password Policy Violation");
        pd.setDetail(String.join("; ", ex.getViolations()));
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidToken(InvalidTokenException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid Token");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication Failed");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    /**
     * Passes through ResponseStatusException HTTP status + message directly.
     * Used by service layer for NOT_FOUND, BAD_REQUEST, etc.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(ex.getStatusCode());
        pd.setTitle(ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString());
        pd.setDetail(ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(pd);
    }

    /**
     * HTTP 409 Conflict — optimistic lock version mismatch.
     * Thrown by Hibernate when the @Version WHERE clause fails (client has a stale version).
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("The resource was modified by another request. Reload and retry.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    /**
     * HTTP 422 — illegal task state transition (e.g. COMPLETED → IN_PROGRESS).
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleIllegalTransition(IllegalStateTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Illegal State Transition");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    /**
     * HTTP 403 — @PreAuthorize check failed (e.g. STANDARD user on an ADMIN endpoint).
     * Spring Security throws AccessDeniedException which, if not caught here, bypasses
     * the @RestControllerAdvice and falls through to the generic 500 handler.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setTitle("Forbidden");
        pd.setDetail("Insufficient permissions for this operation");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal Server Error");
        // No detail or stack trace sent to client
        return ResponseEntity.internalServerError().body(pd);
    }
}
