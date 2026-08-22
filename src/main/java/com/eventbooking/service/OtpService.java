package com.eventbooking.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;


@Service
@Slf4j
@RequiredArgsConstructor
public class OtpService {

    private static final String KEY_PREFIX = "pending-registration:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public record PendingRegistration(String name, String email, String phone, String passwordHash, String otp) {
    }


    public String startRegistration(String name, String email, String phone, String passwordHash) {
        String otp = generateCode();
        PendingRegistration pending = new PendingRegistration(name, email, phone, passwordHash, otp);
        write(email, pending);
        return otp;
    }


    public Optional<PendingRegistration> verify(String email, String submittedOtp) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + normalize(email));
        String json = bucket.get();
        if (json == null) {
            return Optional.empty();
        }
        PendingRegistration pending = read(json);
        if (pending == null || !pending.otp().equals(submittedOtp)) {
            return Optional.empty();
        }
        bucket.delete();
        return Optional.of(pending);
    }


    public boolean canResend(String email) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + normalize(email));
        long remainingMs = bucket.remainTimeToLive();
        if (remainingMs < 0) {
            return true;
        }
        long elapsed = TTL.toMillis() - remainingMs;
        return elapsed >= RESEND_COOLDOWN.toMillis();
    }


    public Optional<String> resend(String email) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + normalize(email));
        String json = bucket.get();
        if (json == null) {
            return Optional.empty();
        }
        PendingRegistration existing = read(json);
        if (existing == null) {
            return Optional.empty();
        }
        String otp = generateCode();
        PendingRegistration refreshed = new PendingRegistration(
                existing.name(), existing.email(), existing.phone(), existing.passwordHash(), otp);
        write(email, refreshed);
        return Optional.of(otp);
    }

    public boolean hasPendingRegistration(String email) {
        return redissonClient.getBucket(KEY_PREFIX + normalize(email)).isExists();
    }

    private void write(String email, PendingRegistration pending) {
        try {
            String json = objectMapper.writeValueAsString(pending);
            redissonClient.<String>getBucket(KEY_PREFIX + normalize(email)).set(json, TTL);
        } catch (Exception e) {
            log.error("Failed to serialize pending registration for {}", email, e);
            throw new IllegalStateException("Could not start registration. Please try again.");
        }
    }

    private PendingRegistration read(String json) {
        try {
            return objectMapper.readValue(json, PendingRegistration.class);
        } catch (Exception e) {
            log.error("Failed to deserialize pending registration", e);
            return null;
        }
    }

    private String generateCode() {
        return String.valueOf(100000 + random.nextInt(900000));
    }

    private String normalize(String email) {
        return email.trim().toLowerCase();
    }
}