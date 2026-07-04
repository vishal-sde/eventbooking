package com.eventbooking.repository;

import com.eventbooking.entity.Booking;
import com.eventbooking.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
   @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.status = 'PENDING' AND b.expiresAt < :now")
   List<Booking> findExpiredPendingBookings(@Param("now")LocalDateTime now);
   @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.bookingRef = :ref")
   Optional<Booking> findByBookingRefWithDetails(@Param("ref") String ref);
    @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event WHERE b.user.id = :userId ORDER BY b.bookedAt DESC")
    List<Booking> findByUserIdWithDetails(@Param("userId") Long userId);
    @Query("SELECT b FROM Booking b JOIN FETCH b.user JOIN FETCH b.event ORDER BY b.bookedAt DESC")
    List<Booking> findAllWithDetails();
}
