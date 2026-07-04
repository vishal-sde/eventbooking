package com.eventbooking.service;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.entity.*;
import com.eventbooking.exception.DuplicateBookingException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.exception.SeatsUnavailableException;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private DistributedLockService lockService;

    @InjectMocks
    private BookingService bookingService;

    private User testUser;
    private Event testEvent;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .name("Test User")
                .email("test@test.com")
                .phone("9876543210")
                .role(Role.USER)
                .build();

        testEvent = Event.builder()
                .id(1L)
                .name("Test Concert")
                .venue("Test Arena")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(100)
                .availableSeats(10)
                .ticketPrice(500.0)
                .status(Status.UPCOMING)
                .build();

        testBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-TEST001")
                .user(testUser)
                .event(testEvent)
                .seatsBooked(2)
                .totalAmount(1000.0)
                .status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .bookedAt(LocalDateTime.now())
                .build();

        setSecurityContext(testUser.getEmail(), "ROLE_USER");
    }

    private void setSecurityContext(String email, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null,
                List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        /**
         * Nested @BeforeEach runs ONLY for tests in this inner class.
         * The Confirm tests won't see this stub, so no UnnecessaryStubbing.
         *
         * WHY THIS STUB IS NEEDED:
         * bookingService.create() calls lockService.executeWithLock().
         * Without this stub, the mock returns null and the supplier never runs.
         * This stub makes the lock a no-op — it just executes the supplier immediately.
         * This is correct for unit tests — we're testing booking logic, not the lock.
         */
        @BeforeEach
        void stubLock() {
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
        }

        @Test
        @DisplayName("creates booking successfully with PENDING status")
        void createsBookingSuccessfully() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(2).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.findByUserIdAndEventIdAndStatus(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);
            when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

            BookingDto.Response response = bookingService.create(request);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(BookingStatus.PENDING);
            verify(bookingRepository).save(any(Booking.class));
        }



        @Test
        @DisplayName("throws SeatsUnavailableException when not enough seats")
        void throwsWhenNotEnoughSeats() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(999).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.findByUserIdAndEventIdAndStatus(any(), any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.create(request))
                    .isInstanceOf(SeatsUnavailableException.class);
        }

        @Test
        @DisplayName("throws DuplicateBookingException when user already booked")
        void throwsOnDuplicateBooking() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(1).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.findByUserIdAndEventIdAndStatus(any(), any(), any()))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.create(request))
                    .isInstanceOf(DuplicateBookingException.class);
        }

        @Test
        @DisplayName("throws IllegalStateException for cancelled event")
        void throwsForCancelledEvent() {
            testEvent.setStatus(Status.CANCELLED);

            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(1).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

            assertThatThrownBy(() -> bookingService.create(request))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("decrements availableSeats after booking")
        void decrementsAvailableSeats() {
            int initialSeats = testEvent.getAvailableSeats();

            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(3).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.findByUserIdAndEventIdAndStatus(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(bookingRepository.save(any())).thenReturn(testBooking);
            when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

            bookingService.create(request);

            verify(eventRepository).save(argThat(e ->
                    e.getAvailableSeats() == initialSeats - 3
            ));
        }

        @Test
        @DisplayName("marks event SOLD_OUT when last seat booked")
        void marksEventSoldOutWhenLastSeatBooked() {
            testEvent.setAvailableSeats(2);

            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(2).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.findByUserIdAndEventIdAndStatus(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(bookingRepository.save(any())).thenReturn(testBooking);
            when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

            bookingService.create(request);

            verify(eventRepository).save(argThat(e ->
                    e.getStatus() == Status.SOLD_OUT
            ));
        }
    }

    @Test
    @DisplayName("throws ResourceNotFoundException when user not found")
    void throwsWhenUserNotFound() {
        BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                .userId(99L).eventId(1L).seatsRequired(1).build();

        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // Fails before lock is even acquired — user lookup happens first
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── confirm() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("confirm()")
    class Confirm {

        // No @BeforeEach here — confirm() doesn't use lockService

        @Test
        @DisplayName("confirms a pending booking")
        void confirmsPendingBooking() {
            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(testBooking));
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            BookingDto.Response response = bookingService.confirm("BK-TEST001");

            assertThat(response).isNotNull();
            verify(bookingRepository).save(argThat(b ->
                    b.getStatus() == BookingStatus.CONFIRMED
            ));
        }

        @Test
        @DisplayName("throws when booking has expired")
        void throwsWhenExpired() {
            testBooking.setExpiresAt(LocalDateTime.now().minusMinutes(1));

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.confirm("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("throws when booking already confirmed")
        void throwsWhenAlreadyConfirmed() {
            testBooking.setStatus(BookingStatus.CONFIRMED);

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(testBooking));

            assertThatThrownBy(() -> bookingService.confirm("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already confirmed");
        }
    }
}