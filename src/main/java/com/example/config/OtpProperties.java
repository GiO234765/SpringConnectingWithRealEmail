package com.example.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Tunable OTP settings, bound from the {@code otp.*} configuration namespace.
 */
@Validated
@ConfigurationProperties(prefix = "otp")
public record OtpProperties(

        @DefaultValue("6")
        @Min(4) @Max(10)
        int length,

        @DefaultValue("5m")
        Duration ttl,

        @DefaultValue("3")
        @Min(1) @Max(10)
        int maxAttempts,

        @DefaultValue("1m")
        Duration resendCooldown,

        @DefaultValue("no-reply@example.com")
        @NotBlank
        String from,

        @DefaultValue("Your verification code")
        @NotBlank
        String subject
) {

    /**
     * Number of leading digits a generated code may contain, used to keep every
     * code exactly {@link #length()} digits long.
     */
    public int codeBound() {
        return (int) Math.pow(10, length);
    }
}
