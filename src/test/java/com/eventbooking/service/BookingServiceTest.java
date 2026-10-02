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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
    @Mock private EmailService emailService;
    @Mock private TransactionTemplate tx;          // NEW

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
                .ticketPrice(new BigDecimal("500.00"))
                .status(Status.UPCOMING)
                .build();

        testBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-TEST001")
                .user(testUser)
                .event(testEvent)
                .seatsBooked(2)
                .totalAmount(new BigDecimal("1000.00"))
                .status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .bookedAt(LocalDateTime.now())
                .build();

        // NEW: the TransactionTemplate is a mock, so make it simply run the callback.
        // lenient() because tests that fail before reaching the transaction don't use it.
        lenient().when(tx.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));

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

        // Makes the lock a no-op: just run the supplier. We're testing booking logic, not Redis.
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
            when(eventRepository.reserveSeats(eq(1L), eq(2), any(LocalDateTime.class))).thenReturn(1);
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            BookingDto.Response response = bookingService.create(request);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(BookingStatus.PENDING);
            verify(bookingRepository).save(any(Booking.class));
        }

        @Test
        @DisplayName("throws SeatsUnavailableException when seats cannot be reserved")
        void throwsWhenNotEnoughSeats() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(999).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            // reserveSeats is not stubbed -> Mockito returns 0 -> "0 rows updated" -> no seats

            assertThatThrownBy(() -> bookingService.create(request))
                    .isInstanceOf(SeatsUnavailableException.class);

            verify(bookingRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws DuplicateBookingException when user already has an active booking")
        void throwsOnDuplicateBooking() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(1).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.existsByUserIdAndEventIdAndStatusIn(eq(1L), eq(1L), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> bookingService.create(request))
                    .isInstanceOf(DuplicateBookingException.class);

            verify(eventRepository, never()).reserveSeats(any(), anyInt(), any());
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

            verify(eventRepository, never()).reserveSeats(any(), anyInt(), any());
        }

        // REPLACES "decrements availableSeats after booking": seats are now changed by
        // an atomic SQL update, so we verify the update is requested with the right amount.
        @Test
        @DisplayName("reserves exactly the requested number of seats")
        void reservesRequestedSeats() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(3).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(eventRepository.reserveSeats(eq(1L), eq(3), any(LocalDateTime.class))).thenReturn(1);
            when(bookingRepository.save(any())).thenReturn(testBooking);

            bookingService.create(request);

            verify(eventRepository).reserveSeats(eq(1L), eq(3), any(LocalDateTime.class));
        }

        // REPLACES "marks event SOLD_OUT when last seat booked": the SOLD_OUT decision is now
        // a SQL statement (markSoldOutIfEmpty), so we verify it is called after the reservation.
        @Test
        @DisplayName("checks for sold-out after reserving seats")
        void checksSoldOutAfterReserving() {
            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(2).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(eventRepository.reserveSeats(eq(1L), eq(2), any(LocalDateTime.class))).thenReturn(1);
            when(bookingRepository.save(any())).thenReturn(testBooking);

            bookingService.create(request);

            var inOrder = inOrder(eventRepository, bookingRepository);
            inOrder.verify(eventRepository).reserveSeats(eq(1L), eq(2), any(LocalDateTime.class));
            inOrder.verify(bookingRepository).save(any(Booking.class));
            inOrder.verify(eventRepository).markSoldOutIfEmpty(eq(1L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("total amount is calculated exactly with BigDecimal")
        void calculatesTotalAmountExactly() {
            testEvent.setTicketPrice(new BigDecimal("0.10"));

            BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                    .userId(1L).eventId(1L).seatsRequired(3).build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(eventRepository.reserveSeats(eq(1L), eq(3), any(LocalDateTime.class))).thenReturn(1);
            when(bookingRepository.save(any(Booking.class))).thenReturn(testBooking);

            bookingService.create(request);

            ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
            verify(bookingRepository).save(captor.capture());
            assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo("0.30");
        }
    }

    @Test
    @DisplayName("throws ResourceNotFoundException when user not found")
    void throwsWhenUserNotFound() {
        BookingDto.CreateRequest request = BookingDto.CreateRequest.builder()
                .userId(99L).eventId(1L).seatsRequired(1).build();

        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    // ── confirm() ────────────────────────────────────────────────────────────
    // Unchanged for now. These will change in Step 5 when confirm() is locked down.

    @Nested
    @DisplayName("confirm()")
    class Confirm {

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