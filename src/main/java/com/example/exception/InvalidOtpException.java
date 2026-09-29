package com.example.exception;

/**
 * Raised when the submitted OTP does not match the stored one.
 */
public class InvalidOtpException extends RuntimeException {

    public InvalidOtpException(String message) {
        super(message);
    }
}
