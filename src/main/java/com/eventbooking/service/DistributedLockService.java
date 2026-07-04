package com.eventbooking.service;

import com.eventbooking.exception.LockAcquisitionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@Slf4j
@RequiredArgsConstructor
public class DistributedLockService {

    private static final String LOCK_PREFIX = "event-booking:";
    private static final int WAIT_SECONDS = 5;

    private final RedissonClient redissonClient;

    @Transactional
    public <T> T executeWithLock(Long eventId, Supplier<T> supplier) {
        String lockKey = LOCK_PREFIX + eventId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            // waitTime=5s: how long to queue before giving up
            // leaseTime=-1: watchdog mode — auto-renews TTL while thread is alive
            // Without watchdog: lock expires after N seconds even if your transaction
            // is still running → another thread grabs lock → race condition
            acquired = lock.tryLock(WAIT_SECONDS, -1, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Lock not acquired for event {} after {}s", eventId, WAIT_SECONDS);
                throw new LockAcquisitionException(
                        "Event is under high demand. Please try again in a moment."
                );
            }
            log.debug("Lock acquired: {} thread: {}", lockKey, Thread.currentThread().getName());
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("Booking interrupted. Please retry.");
        } finally {
            // isHeldByCurrentThread() check is critical —
            // prevents unlocking a lock held by a different thread
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Lock released: {}", lockKey);
            }
        }
    }
}