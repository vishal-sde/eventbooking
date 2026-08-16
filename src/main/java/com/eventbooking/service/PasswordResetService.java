package com.eventbooking.service;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * Holds password-reset codes in Redis. Simpler than OtpService's
 * PendingRegistration — a reset code has nothing to carry alongside it,
 * the account already exists, so it's just a bare code with a TTL.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String KEY_PREFIX = "password-reset:";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final RedissonClient redissonClient;
    private final SecureRandom random = new SecureRandom();

    public String generate(String email) {
        String code = String.valueOf(100000 + random.nextInt(900000));
        redissonClient.<String>getBucket(KEY_PREFIX + normalize(email)).set(code, TTL);
        return code;
    }

    public boolean verify(String email, String submittedCode) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + normalize(email));
        String stored = bucket.get();
        if (stored == null || !stored.equals(submittedCode)) {
            return false;
        }
        bucket.delete();
        return true;
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

    private String normalize(String email) {
        return email.trim().toLowerCase();
    }
}