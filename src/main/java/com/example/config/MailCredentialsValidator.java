package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Warns at startup when the SMTP settings are incomplete, so the problem shows
 * up in the log at boot instead of as an opaque 502 on the first request.
 */
@Component
public class MailCredentialsValidator {

    private static final Logger log = LoggerFactory.getLogger(MailCredentialsValidator.class);

    private final String host;
    private final String port;
    private final String username;
    private final String password;

    public MailCredentialsValidator(
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:}") String port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    void validate() {
        log.info("SMTP target: {}:{} as {}", host.isBlank() ? "<no host>" : host, port, mask(username));

        if (host.isBlank()) {
            log.warn("spring.mail.host is not set. Set MAIL_HOST to your SMTP server.");
        }
        if (username.isBlank() || password.isBlank()) {
            log.warn("""

                    ============================================================
                    Mail credentials are NOT configured - OTP emails will FAIL.
                      spring.mail.username : {}
                      spring.mail.password : {}
                    Set the MAIL_USERNAME and MAIL_PASSWORD environment
                    variables, then restart the application.
                    For Gmail: enable 2-Step Verification, create an App
                    Password at https://myaccount.google.com/apppasswords
                    and use that 16-character value as MAIL_PASSWORD.
                    ============================================================
                    """,
                    username.isBlank() ? "<empty>" : "set",
                    password.isBlank() ? "<empty>" : "set");
        }
    }

    private static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "<not configured>";
        }
        int at = value.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }
}
