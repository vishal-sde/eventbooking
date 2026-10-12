package com.eventbooking.repository;

import com.eventbooking.entity.Booking;
import com.eventbooking.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking,Long> {
   Optional<Booking> findByBookingRef(String ref);
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select b from Booking b where b.bookingRef = :ref")
   Optional<Booking> findByBookingRefForUpdate(@Param("ref") String ref);
   Optional<Booking> findByUserIdAndEventIdAndStatus(Long userId, Long eventId, BookingStatus status);
   List<Booking> findByUserIdOrderByBookedAtDesc(Long userId);
   List<Booking> findAllByOrderByBookedAtDesc();
   List<Booking> findByEventIdAndStatus(Long eventId, BookingStatus status);
   @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.status in( 'PENDING','PAYMENT_PENDING') AND b.expiresAt < :now")
   List<Booking> findExpiredPendingBookings(@Param("now")LocalDateTime now);
   @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.bookingRef = :ref")
   Optional<Booking> findByBookingRefWithDetails(@Param("ref") String ref);
    @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.user.id = :userId ORDER BY b.bookedAt DESC")
    List<Booking> findByUserIdWithDetails(@Param("userId") Long userId);
    @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event ORDER BY b.bookedAt DESC")
    List<Booking> findAllWithDetails();
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update Booking b
       set b.status = com.eventbooking.entity.BookingStatus.CANCELLED,
           b.cancelledAt = :now
     where b.id = :id
       and b.status in (com.eventbooking.entity.BookingStatus.PENDING,
                        com.eventbooking.entity.BookingStatus.PAYMENT_PENDING,
                        com.eventbooking.entity.BookingStatus.CONFIRMED)
    """)
    int claimForCancel(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update Booking b
       set b.status = com.eventbooking.entity.BookingStatus.CANCELLED,
           b.cancelledAt = :now
     where b.id = :id
       and b.status in (com.eventbooking.entity.BookingStatus.PENDING,
                        com.eventbooking.entity.BookingStatus.PAYMENT_PENDING)
       and b.expiresAt < :now
    """)
    int claimForExpiry(@Param("id") Long id, @Param("now") LocalDateTime now);

    boolean existsByUserIdAndEventIdAndStatusIn(Long userId, Long eventId,
                                                java.util.Collection<BookingStatus> statuses);
    // Atomic confirm: succeeds only if still active AND not expired. Closes the race with the expiry job.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update Booking b
       set b.status = com.eventbooking.entity.BookingStatus.CONFIRMED,
           b.expiresAt = null
     where b.id = :id
       and b.status in (com.eventbooking.entity.BookingStatus.PENDING,
                        com.eventbooking.entity.BookingStatus.PAYMENT_PENDING)
       and b.expiresAt > :now
    """)
    int confirmIfActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    // Used when an event is cancelled: every live booking of that event is cancelled in one statement.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    update Booking b
       set b.status = com.eventbooking.entity.BookingStatus.CANCELLED,
           b.cancelledAt = :now
     where b.event.id = :eventId
       and b.status in (com.eventbooking.entity.BookingStatus.PENDING,
                        com.eventbooking.entity.BookingStatus.PAYMENT_PENDING,
                        com.eventbooking.entity.BookingStatus.CONFIRMED)
    """)
    int cancelActiveForEvent(@Param("eventId") Long eventId, @Param("now") LocalDateTime now);
}
