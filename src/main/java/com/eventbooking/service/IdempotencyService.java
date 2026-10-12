package com.eventbooking.service;

import com.eventbooking.entity.IdempotencyKey;
import com.eventbooking.entity.IdempotencyState;
import com.eventbooking.exception.IdempotencyKeyReusedException;
import com.eventbooking.exception.RequestInProgressException;
import com.eventbooking.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final Duration KEY_TTL = Duration.ofHours(24);
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final IdempotencyKeyRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public record Result<T>(T body, boolean replayed) {}

    /**
     * Runs {@code action} at most once per (user, endpoint, key). Must NOT be called
     * inside an existing transaction: the key row has to commit independently.
     */
    public <T> Result<T> execute(Long userId, String endpoint, String key, Object request,
                                 Class<T> responseType, Supplier<T> action) {
        String hash = sha256(objectMapper.writeValueAsString(request));
        LocalDateTime now = LocalDateTime.now();

        IdempotencyKey started;
        try {
            started = tx.execute(status -> repository.saveAndFlush(IdempotencyKey.builder()
                    .userId(userId).endpoint(endpoint).idemKey(key).requestHash(hash)
                    .state(IdempotencyState.IN_PROGRESS)
                    .createdAt(now).expiresAt(now.plus(KEY_TTL))
                    .build()));
        } catch (DataIntegrityViolationException duplicate) {
            if(!isDuplicateKey(duplicate)){
                throw duplicate;
            }
            return replayOrReject(userId, endpoint, key, hash, request, responseType, action);
        }
        Long keyId = started.getId();

        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            // Nothing happened (the action's own transaction rolled back), so free the key for a clean retry.
            tx.executeWithoutResult(status -> repository.deleteIfInProgress(keyId));
            throw e;
        }

        try {
            String body = objectMapper.writeValueAsString(response);
            tx.executeWithoutResult(status -> repository.markCompleted(keyId, 201, body));
        } catch (RuntimeException e) {
            // The action succeeded. Never fail the caller because storing the replay failed;
            // the row stays IN_PROGRESS and is treated as stale after STALE_AFTER.
            log.error("Could not store idempotent response for key {}: {}", key, e.getMessage());
        }
        return new Result<>(response, false);
    }

    private <T> Result<T> replayOrReject(Long userId, String endpoint, String key, String hash,
                                         Object request, Class<T> responseType, Supplier<T> action) {
        IdempotencyKey existing = repository.findByUserIdAndEndpointAndIdemKey(userId, endpoint, key)
                .orElseThrow(() -> new RequestInProgressException("Please retry the request"));

        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException(
                    "This Idempotency-Key was already used with a different request");
        }
        if (existing.getState() == IdempotencyState.COMPLETED) {
            return new Result<>(objectMapper.readValue(existing.getResponseBody(), responseType), true);
        }
        if (existing.getCreatedAt().isBefore(LocalDateTime.now().minus(STALE_AFTER))) {
            // The original attempt most likely died mid-request. Clear it and run again.
            tx.executeWithoutResult(status -> repository.deleteIfInProgress(existing.getId()));
            return execute(userId, endpoint, key, request, responseType, action);
        }
        throw new RequestInProgressException("A request with this Idempotency-Key is still being processed");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    /** MySQL error 1062 = duplicate entry for a unique key. */
    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getErrorCode() == 1062) {
                return true;
            }
        }
        return false;
    }
}