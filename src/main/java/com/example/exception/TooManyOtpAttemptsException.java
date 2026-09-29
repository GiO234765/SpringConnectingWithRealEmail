package com.example.exception;

/**
 * Raised when the caller is submitting OTP guesses too aggressively.
 */
public class TooManyOtpAttemptsException extends RuntimeException {

    public TooManyOtpAttemptsException(String message) {
        super(message);
    }
}
