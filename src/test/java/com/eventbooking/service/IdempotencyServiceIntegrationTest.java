package com.eventbooking.service;

import com.eventbooking.AbstractIntegrationTest;
import com.eventbooking.entity.Role;
import com.eventbooking.entity.User;
import com.eventbooking.exception.IdempotencyKeyReusedException;
import com.eventbooking.repository.IdempotencyKeyRepository;
import com.eventbooking.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceIntegrationTest extends AbstractIntegrationTest {

    record Req(long eventId, int seats) {}

    @Autowired private IdempotencyService idempotencyService;
    @Autowired private IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User user;
    private Long userId;

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        user = userRepository.save(User.builder()
                .name("IdemUser")
                .email("idem" + n + "@test.com")
                .phone("8" + String.format("%09d", n % 1_000_000_000L))
                .password(passwordEncoder.encode("password123"))
                .role(Role.USER)
                .emailVerified(true)
                .build());
        userId = user.getId();
    }

    @AfterEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAll();   // must go first: it has a foreign key to users
        userRepository.deleteById(userId);
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("same key + same request runs the action once and replays the result")
    void sameKeyRunsOnce() {
        String key = newKey();
        AtomicInteger calls = new AtomicInteger();

        var first = idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class,
                () -> "result-" + calls.incrementAndGet());
        var second = idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class,
                () -> "result-" + calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("same key with a different request is rejected")
    void sameKeyDifferentRequestRejected() {
        String key = newKey();
        idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class, () -> "ok");

        assertThatThrownBy(() ->
                idempotencyService.execute(userId, "TEST", key, new Req(1, 5), String.class, () -> "ok"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("a failed action releases the key so a retry runs again")
    void failedActionReleasesKey() {
        String key = newKey();

        assertThatThrownBy(() ->
                idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class,
                        () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        var retry = idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class, () -> "ok");
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.body()).isEqualTo("ok");
    }

    @Test
    @DisplayName("10 simultaneous retries with the same key run the action exactly once")
    void concurrentRetriesRunOnce() throws Exception {
        String key = newKey();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    idempotencyService.execute(userId, "TEST", key, new Req(1, 2), String.class, () -> {
                        calls.incrementAndGet();
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        return "ok";
                    });
                } catch (RuntimeException ignored) {
                    // "request in progress" is an acceptable answer for the losers
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(calls.get()).isEqualTo(1);
    }
}