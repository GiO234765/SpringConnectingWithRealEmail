package com.example.Controller;

import com.example.otp.dto.OtpRequest;
import com.example.otp.dto.OtpVerificationRequest;
import com.example.otp.service.OtpService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class OtpController {

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send-otp")
    public ResponseEntity<?> sendOtp(@Valid @RequestBody OtpRequest request) {
        otpService.sendOtpEmail(request.getEmail());
        // Deliberately generic: do not reveal whether the address is usable.
        return ResponseEntity.ok(Map.of("status", "SENT", "message", "If the address is valid, an OTP has been sent."));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@Valid @RequestBody OtpVerificationRequest request) {
        otpService.verifyOtp(request.getEmail(), request.getOtp());
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "OTP verified successfully!"));
    }
}
