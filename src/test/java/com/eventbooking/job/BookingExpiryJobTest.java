package com.eventbooking.job;

import com.eventbooking.entity.*;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingExpiryJob")
class BookingExpiryJobTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventRepository eventRepository;
    @Mock private TransactionTemplate tx;          // NEW

    @InjectMocks
    private BookingExpiryJob bookingExpiryJob;

    private Event event;
    private Booking expiredBooking;

    @BeforeEach
    void setUp() {
        event = Event.builder()
                .id(1L)
                .name("Test Concert")
                .venue("Test Arena")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(100)
                .availableSeats(95)
                .ticketPrice(new BigDecimal("500.00"))
                .status(Status.UPCOMING)
                .build();

        expiredBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-EXPIRED1")
                .user(User.builder().id(1L).name("Test User").email("test@test.com")
                        .phone("9876543210").role(Role.USER).build())
                .event(event)
                .seatsBooked(2)
                .totalAmount(new BigDecimal("1000.00"))
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().minusMinutes(5))   // already expired
                .build();

        // NEW: the transaction is a no-op that just runs the callback.
        lenient().when(tx.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private Booking secondBooking(long id, String ref, int seats) {
        return Booking.builder()
                .id(id)
                .bookingRef(ref)
                .user(expiredBooking.getUser())
                .event(event)
                .seatsBooked(seats)
                .totalAmount(new BigDecimal("500.00"))
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }

    @Test
    @DisplayName("does nothing when no expired bookings exist")
    void doesNothingWhenNoExpiredBookings() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of());

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository, never()).claimForExpiry(any(), any());
        verify(eventRepository, never()).releaseSeats(any(), anyInt(), any());
    }

    @Test
    @DisplayName("cancels expired booking and restores seats to event")
    void cancelsExpiredBookingAndRestoresSeats() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.claimForExpiry(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository).claimForExpiry(eq(1L), any(LocalDateTime.class));
        verify(eventRepository).releaseSeats(eq(1L), eq(2), any(LocalDateTime.class));
    }

    // NEW: replaces the old SOLD_OUT and "cap at totalSeats" unit tests. Those rules now
    // live in the releaseSeats SQL (CASE / LEAST), so they need a real-DB test, not a mock.
    @Test
    @DisplayName("does NOT restore seats if the booking was already handled by someone else")
    void doesNotRestoreSeatsWhenClaimLost() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.claimForExpiry(eq(1L), any(LocalDateTime.class))).thenReturn(0);

        bookingExpiryJob.releaseExpiredBookings();

        verify(eventRepository, never()).releaseSeats(any(), anyInt(), any());
    }

    @Test
    @DisplayName("continues processing remaining bookings even if one fails")
    void continuesProcessingAfterOneFailure() {
        Booking second = secondBooking(2L, "BK-EXPIRED2", 1);

        when(bookingRepository.findExpiredPendingBookings(any()))
                .thenReturn(List.of(expiredBooking, second));
        when(bookingRepository.claimForExpiry(eq(1L), any()))
                .thenThrow(new RuntimeException("DB error on first"));
        when(bookingRepository.claimForExpiry(eq(2L), any())).thenReturn(1);

        // Must not propagate the exception
        bookingExpiryJob.releaseExpiredBookings();

        // The first one failed, the second one was still processed
        verify(eventRepository).releaseSeats(eq(1L), eq(1), any(LocalDateTime.class));
        verify(eventRepository, times(1)).releaseSeats(any(), anyInt(), any());
    }

    @Test
    @DisplayName("processes multiple expired bookings in one run")
    void processesMultipleExpiredBookings() {
        Booking another = secondBooking(3L, "BK-EXPIRED3", 1);

        when(bookingRepository.findExpiredPendingBookings(any()))
                .thenReturn(List.of(expiredBooking, another));
        when(bookingRepository.claimForExpiry(anyLong(), any())).thenReturn(1);

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository, times(2)).claimForExpiry(anyLong(), any(LocalDateTime.class));
        verify(eventRepository, times(2)).releaseSeats(eq(1L), anyInt(), any(LocalDateTime.class));
    }
}