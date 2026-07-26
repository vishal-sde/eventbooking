package com.eventbooking.repository;

import com.eventbooking.entity.Category;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {


    @Query("""
            SELECT e FROM Event e
            WHERE (:search IS NULL
                   OR LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(e.venue) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(e.city) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:status IS NULL OR e.status = :status)
              AND (:minSeats IS NULL OR e.availableSeats >= :minSeats)
              AND (:category IS NULL OR e.category = :category)
              AND (:city IS NULL OR LOWER(e.city) = LOWER(:city))
              AND (:minPrice IS NULL OR e.ticketPrice >= :minPrice)
              AND (:maxPrice IS NULL OR e.ticketPrice <= :maxPrice)
              AND (:dateFrom IS NULL OR e.eventDate >= :dateFrom)
            """)
    Page<Event> search(
            @Param("search") String search,
            @Param("status") Status status,
            @Param("minSeats") Integer minSeats,
            @Param("category") Category category,
            @Param("city") String city,
            @Param("minPrice") Double minPrice,
            @Param("maxPrice") Double maxPrice,
            @Param("dateFrom") LocalDateTime dateFrom,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
}