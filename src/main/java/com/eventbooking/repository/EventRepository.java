package com.eventbooking.repository;

import com.eventbooking.entity.Category;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import org.springframework.security.core.parameters.P;

import java.math.BigDecimal;
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
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("dateFrom") LocalDateTime dateFrom,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT e FROM Event e
            WHERE e.status IN (com.eventbooking.entity.Status.UPCOMING, com.eventbooking.entity.Status.SOLD_OUT)
              AND e.eventDate <= :now
            """)
    java.util.List<Event> findPastEventsStillOpen(@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true,flushAutomatically = true)
    @Query("""
            update Event e
            set e.availableSeats = e.availableSeats - :n,
                       e.version = e.version + 1,
                       e.updatedAt = :now
                 where e.id = :id
                   and e.status = com.eventbooking.entity.Status.UPCOMING
                   and e.availableSeats >= :n
            """)
    int reserveSeats(@Param("id") Long id,@Param("n") int n,@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true,flushAutomatically = true)
    @Query("""
            update Event e
                   set e.availableSeats = least(e.totalSeats, e.availableSeats + :n),
                       e.status = case when e.status = com.eventbooking.entity.Status.SOLD_OUT
                                       then com.eventbooking.entity.Status.UPCOMING
                                       else e.status end,
                       e.version = e.version + 1,
                       e.updatedAt = :now
                 where e.id = :id
            """)
    int releaseSeats(@Param("id") Long id,@Param("n") int n,@Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update Event e
       set e.status = com.eventbooking.entity.Status.SOLD_OUT,
           e.version = e.version + 1,
           e.updatedAt = :now
     where e.id = :id
       and e.availableSeats = 0
       and e.status = com.eventbooking.entity.Status.UPCOMING
    """)
    int markSoldOutIfEmpty(@Param("id") Long id, @Param("now") LocalDateTime now);
}