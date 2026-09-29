package com.example.otp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.config.OtpProperties;
import com.example.exception.InvalidOtpException;
import com.example.exception.OtpCooldownException;
import com.example.exception.TooManyOtpAttemptsException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class OtpServiceTest {

    private JavaMailSender mailSender;
    private OtpService otpService;

    private static OtpProperties props(Duration ttl, int maxAttempts, Duration cooldown) {
        return new OtpProperties(6, ttl, maxAttempts, cooldown, "no-reply@example.com", "Your code");
    }

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        otpService = new OtpService(mailSender, props(Duration.ofMinutes(5), 3, Duration.ofMinutes(1)));
    }

    private String captureSentOtp() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        String text = captor.getValue().getText();
        String otp = text.substring(text.indexOf("code is: ") + "code is: ".length()).split("\\D")[0];
        return otp.substring(0, 6);
    }

    @Test
    void sendsAndAcceptsCorrectOtp() {
        otpService.sendOtpEmail("user@example.com");
        String otp = captureSentOtp();

        assertThat(otp).hasSize(6).containsOnlyDigits();

        otpService.verifyOtp("user@example.com", otp);
    }

    @Test
    void treatsEmailsCaseInsensitivelyAndTrimsWhitespace() {
        otpService.sendOtpEmail("  User@Example.COM ");
        String otp = captureSentOtp();

        otpService.verifyOtp("user@example.com", otp);
    }

    @Test
    void rejectsWrongOtp() {
        otpService.sendOtpEmail("user@example.com");

        assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", "000000"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void rejectsUnknownEmail() {
        assertThatThrownBy(() -> otpService.verifyOtp("nobody@example.com", "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void locksOutAfterMaxAttempts() {
        otpService.sendOtpEmail("user@example.com");

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", "000000"))
                    .isInstanceOf(InvalidOtpException.class);
        }
        assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", "000000"))
                .isInstanceOf(TooManyOtpAttemptsException.class);
    }

    @Test
    void rejectsResendDuringCooldown() {
        otpService.sendOtpEmail("user@example.com");

        assertThatThrownBy(() -> otpService.sendOtpEmail("user@example.com"))
                .isInstanceOf(OtpCooldownException.class);
    }

    @Test
    void allowsResendOnceCooldownElapsed() {
        otpService = new OtpService(mailSender, props(Duration.ofMinutes(5), 3, Duration.ZERO));
        otpService.sendOtpEmail("user@example.com");
        otpService.sendOtpEmail("user@example.com");
    }

    @Test
    void codeIsSingleUse() {
        otpService.sendOtpEmail("user@example.com");
        String otp = captureSentOtp();

        otpService.verifyOtp("user@example.com", otp);

        assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", otp))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void dropsStoredOtpWhenDeliveryFails() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> otpService.sendOtpEmail("user@example.com"))
                .isInstanceOf(MailSendException.class);

        assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", "123456"))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void rejectsExpiredOtp() {
        otpService = new OtpService(mailSender, props(Duration.ofMillis(1), 3, Duration.ZERO));
        otpService.sendOtpEmail("user@example.com");
        String otp = captureSentOtp();

        try {
            Thread.sleep(20);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        assertThatThrownBy(() -> otpService.verifyOtp("user@example.com", otp))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void usesConfiguredSenderAndSubject() {
        otpService.sendOtpEmail("user@example.com");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("no-reply@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("Your code");
        assertThat(captor.getValue().getTo()).containsExactly("user@example.com");
    }
}
