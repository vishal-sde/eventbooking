package com.eventbooking.repository;

import com.eventbooking.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndEndpointAndIdemKey(Long userId, String endpoint, String idemKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update IdempotencyKey k
           set k.state = com.eventbooking.entity.IdempotencyState.COMPLETED,
               k.responseCode = :code, k.responseBody = :body
         where k.id = :id
        """)
    int markCompleted(@Param("id") Long id, @Param("code") int code, @Param("body") String body);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        delete from IdempotencyKey k
         where k.id = :id and k.state = com.eventbooking.entity.IdempotencyState.IN_PROGRESS
        """)
    int deleteIfInProgress(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}