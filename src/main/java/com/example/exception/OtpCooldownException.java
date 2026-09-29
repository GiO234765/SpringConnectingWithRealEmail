package com.example.exception;

/**
 * Raised when an OTP request arrives before the resend cooldown has elapsed.
 * Carries the number of seconds the caller must wait.
 */
public class OtpCooldownException extends RuntimeException {

    private final long retryAfterSeconds;

    public OtpCooldownException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
