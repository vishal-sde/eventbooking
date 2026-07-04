package com.eventbooking.repository;

import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Single query handles all combinations:
     * - search by name or venue (case-insensitive)
     * - filter by status
     * - filter by minimum available seats
     * - pagination via Pageable
     *
     * Every parameter is optional — null means "ignore this filter".
     * LOWER() ensures case-insensitive search without extra config.
     */
    @Query("""
            SELECT e FROM Event e
            WHERE (:search IS NULL
                   OR LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:status IS NULL OR e.status = :status)
              AND (:minSeats IS NULL OR e.availableSeats >= :minSeats)
            """)
    Page<Event> search(
            @Param("search") String search,
            @Param("status") Status status,
            @Param("minSeats") Integer minSeats,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
}