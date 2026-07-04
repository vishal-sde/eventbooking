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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingExpiryJob")
class BookingExpiryJobTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventRepository eventRepository;

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
                .availableSeats(95)   // 5 seats currently "held" by PENDING bookings
                .ticketPrice(500.0)
                .status(Status.UPCOMING)
                .build();

        expiredBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-EXPIRED1")
                .user(User.builder().id(1L).name("Test User").email("test@test.com")
                        .phone("9876543210").role(Role.USER).build())
                .event(event)
                .seatsBooked(2)
                .totalAmount(1000.0)
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().minusMinutes(5))   // already expired
                .build();
    }

    @Test
    @DisplayName("does nothing when no expired bookings exist")
    void doesNothingWhenNoExpiredBookings() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of());

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository, never()).save(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancels expired booking and restores seats to event")
    void cancelsExpiredBookingAndRestoresSeats() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository).save(argThat(b ->
                b.getStatus() == BookingStatus.CANCELLED &&
                        b.getCancelledAt() != null
        ));
        verify(eventRepository).save(argThat(e ->
                e.getAvailableSeats() == 97   // 95 + 2 restored
        ));
    }

    @Test
    @DisplayName("restores SOLD_OUT event to UPCOMING when seats become available")
    void restoresSoldOutEventToUpcoming() {
        event.setAvailableSeats(0);
        event.setStatus(Status.SOLD_OUT);
        expiredBooking.setSeatsBooked(3);

        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        bookingExpiryJob.releaseExpiredBookings();

        verify(eventRepository).save(argThat(e ->
                e.getStatus() == Status.UPCOMING &&
                        e.getAvailableSeats() == 3
        ));
    }

    @Test
    @DisplayName("caps restored seats at totalSeats — never overflows")
    void capsRestoredSeatsAtTotalSeats() {
        // Seats somehow drifted — restored seats must not exceed totalSeats
        event.setTotalSeats(100);
        event.setAvailableSeats(99);
        expiredBooking.setSeatsBooked(5);  // 99 + 5 would be 104 — must cap at 100

        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        bookingExpiryJob.releaseExpiredBookings();

        verify(eventRepository).save(argThat(e ->
                e.getAvailableSeats() <= e.getTotalSeats()
        ));
    }

    @Test
    @DisplayName("continues processing remaining bookings even if one fails")
    void continuesProcessingAfterOneFailure() {
        // Build a second booking that will be processed
        Booking secondBooking = Booking.builder()
                .id(2L)
                .bookingRef("BK-EXPIRED2")
                .user(expiredBooking.getUser())
                .event(event)
                .seatsBooked(1)
                .totalAmount(500.0)
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().minusMinutes(5))
                .build();

        when(bookingRepository.findExpiredPendingBookings(any()))
                .thenReturn(List.of(expiredBooking, secondBooking));

        // First save throws, second must still run
        when(bookingRepository.save(any()))
                .thenThrow(new RuntimeException("DB error on first"))
                .thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Should not propagate the exception
        bookingExpiryJob.releaseExpiredBookings();

        // Attempted to save at least once (the second one succeeds)
        verify(bookingRepository, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("sets cancelledAt timestamp on expired booking")
    void setsCancelledAtTimestamp() {
        when(bookingRepository.findExpiredPendingBookings(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredBooking));
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository).save(argThat(b ->
                b.getCancelledAt() != null
        ));
    }

    @Test
    @DisplayName("processes multiple expired bookings in one run")
    void processesMultipleExpiredBookings() {
        Booking anotherExpired = Booking.builder()
                .id(3L)
                .bookingRef("BK-EXPIRED3")
                .user(expiredBooking.getUser())
                .event(event)
                .seatsBooked(1)
                .totalAmount(500.0)
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now().minusMinutes(10))
                .expiresAt(LocalDateTime.now().minusMinutes(3))
                .build();

        when(bookingRepository.findExpiredPendingBookings(any()))
                .thenReturn(List.of(expiredBooking, anotherExpired));
        when(bookingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        bookingExpiryJob.releaseExpiredBookings();

        verify(bookingRepository, times(2)).save(any(Booking.class));
        verify(eventRepository, times(2)).save(any(Event.class));
    }
}