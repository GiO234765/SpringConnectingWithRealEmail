package com.example.exception;

import jakarta.mail.AuthenticationFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into consistent RFC 9457 problem responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String BASE_TYPE = "https://example.com/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = create(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid.");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(create(HttpStatus.BAD_REQUEST, "Malformed request", "Request body could not be parsed."));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ProblemDetail> handleInvalidOtp(InvalidOtpException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(create(HttpStatus.UNAUTHORIZED, "Invalid OTP", ex.getMessage()));
    }

    @ExceptionHandler(TooManyOtpAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyAttempts(TooManyOtpAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(create(HttpStatus.LOCKED, "Too many attempts", ex.getMessage()));
    }

    @ExceptionHandler(OtpCooldownException.class)
    public ResponseEntity<ProblemDetail> handleCooldown(OtpCooldownException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(create(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex.getMessage()));
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<ProblemDetail> handleMailFailure(MailException ex) {
        Throwable root = rootCause(ex);
        log.error("Mail delivery failed: {}", root.getClass().getSimpleName() + ": " + root.getMessage(), ex);

        // Never leak SMTP internals verbatim, but tell the caller what to fix.
        if (hasCause(ex, AuthenticationFailedException.class)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(create(HttpStatus.BAD_GATEWAY, "Email delivery failed",
                            "The mail server rejected the credentials. Check MAIL_USERNAME / MAIL_PASSWORD "
                                    + "- Gmail requires an App Password, not your account password."));
        }
        if (hasCause(ex, SocketTimeoutException.class) || hasCause(ex, ConnectException.class)
                || hasCause(ex, UnknownHostException.class)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(create(HttpStatus.BAD_GATEWAY, "Email delivery failed",
                            "Could not reach the mail server. Check MAIL_HOST / MAIL_PORT and network access "
                                    + "(some networks block port 587)."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(create(HttpStatus.BAD_GATEWAY, "Email delivery failed",
                        "We could not send the email right now. Please try again later."));
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private ProblemDetail create(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE_TYPE + status.value()));
        return problem;
    }
}
