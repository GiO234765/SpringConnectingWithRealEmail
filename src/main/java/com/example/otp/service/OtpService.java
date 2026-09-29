package com.example.otp.service;

import com.example.config.OtpProperties;
import com.example.exception.InvalidOtpException;
import com.example.exception.OtpCooldownException;
import com.example.exception.TooManyOtpAttemptsException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final JavaMailSender mailSender;
    private final OtpProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** email (normalized) -> the currently valid OTP, or empty when none is active. */
    private final Map<String, OtpData> otpStorage = new ConcurrentHashMap<>();

    public OtpService(JavaMailSender mailSender, OtpProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @param hashedOtp SHA-256 of the issued code; the plaintext is never retained.
     * @param expiresAt when the code stops being accepted.
     * @param sentAt    when the code was issued, used to enforce the resend cooldown.
     * @param attempts  failed verification attempts so far.
     */
    private record OtpData(String hashedOtp, Instant expiresAt, Instant sentAt, int attempts) {}

    /**
     * Normalizes an address so {@code User@Example.com} and {@code user@example.com }
     * share one OTP slot.
     */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Issues a code for the given address and emails it.
     *
     * @return the number of seconds until another OTP may be requested.
     */
    public long sendOtpEmail(String recipientEmail) {
        String email = normalizeEmail(recipientEmail);
        Instant now = Instant.now();

        OtpData existing = otpStorage.get(email);
        if (existing != null && existing.sentAt().plus(properties.resendCooldown()).isAfter(now)) {
            long wait = Duration.between(now, existing.sentAt().plus(properties.resendCooldown())).toSeconds();
            throw new OtpCooldownException("Please wait before requesting another code.", Math.max(wait, 1));
        }

        String otp = generateOtp();
        otpStorage.put(email, new OtpData(hash(otp), now.plus(properties.ttl()), now, 0));

        try {
            mailSender.send(buildMessage(email, otp));
        } catch (MailException ex) {
            // Never leave behind a code the recipient never received.
            otpStorage.remove(email);
            log.error("Failed to send OTP email to {}", email, ex);
            throw ex;
        }

        log.info("OTP issued for {} (expires in {})", email, properties.ttl());
        return 0;
    }

    /**
     * Validates a submitted code, consuming it on success.
     *
     * @throws InvalidOtpException          if the code is wrong, unknown or expired
     * @throws TooManyOtpAttemptsException  if the attempt limit was reached
     */
    public void verifyOtp(String recipientEmail, String submittedOtp) {
        String email = normalizeEmail(recipientEmail);
        OtpData data = otpStorage.get(email);

        if (data == null) {
            throw new InvalidOtpException("Invalid or expired OTP.");
        }

        if (Instant.now().isAfter(data.expiresAt())) {
            otpStorage.remove(email);
            throw new InvalidOtpException("Invalid or expired OTP.");
        }

        if (!MessageDigest.isEqual(data.hashedOtp().getBytes(StandardCharsets.UTF_8),
                                   hash(submittedOtp).getBytes(StandardCharsets.UTF_8))) {
            int attempts = data.attempts() + 1;
            if (attempts >= properties.maxAttempts()) {
                otpStorage.remove(email);
                throw new TooManyOtpAttemptsException("Too many incorrect attempts. Request a new code.");
            }
            otpStorage.put(email, new OtpData(data.hashedOtp(), data.expiresAt(), data.sentAt(), attempts));
            throw new InvalidOtpException("Invalid or expired OTP.");
        }

        otpStorage.remove(email);
    }

    /**
     * Drops expired codes so the in-memory map cannot grow without bound when
     * clients request codes they never verify.
     */
    @Scheduled(fixedDelayString = "${otp.cleanup-interval:60000}")
    public void purgeExpiredOtps() {
        Instant now = Instant.now();
        int before = otpStorage.size();
        otpStorage.values().removeIf(data -> now.isAfter(data.expiresAt()));
        int removed = before - otpStorage.size();
        if (removed > 0) {
            log.debug("Purged {} expired OTP(s)", removed);
        }
    }

    private String generateOtp() {
        int bound = properties.codeBound();
        int value = secureRandom.nextInt(bound);
        return String.format(Locale.ROOT, "%0" + properties.length() + "d", value);
    }

    private SimpleMailMessage buildMessage(String to, String otp) {
        long minutes = properties.ttl().toMinutes();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(to);
        message.setSubject(properties.subject());
        message.setText("Hello,\n\nYour verification code is: " + otp
                + "\n\nThis code expires in " + minutes + " minutes and can only be used once."
                + "\nDo not share it with anyone. If you did not request it, ignore this email.");
        return message;
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
