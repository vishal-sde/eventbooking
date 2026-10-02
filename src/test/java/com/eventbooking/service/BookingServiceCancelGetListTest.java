package com.eventbooking.service;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.entity.*;
import com.eventbooking.exception.ResourceNotFoundException;
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
@DisplayName("BookingService — cancel / get / list")
class BookingServiceCancelGetListTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private DistributedLockService lockService;
    @Mock private TransactionTemplate tx;          // NEW

    @InjectMocks
    private BookingService bookingService;

    private User testUser;
    private Event testEvent;
    private Booking confirmedBooking;

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
                .availableSeats(90)
                .ticketPrice(new BigDecimal("500.00"))
                .status(Status.UPCOMING)
                .build();

        confirmedBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-TEST001")
                .user(testUser)
                .event(testEvent)
                .seatsBooked(2)
                .totalAmount(new BigDecimal("1000.00"))
                .status(BookingStatus.CONFIRMED)
                .bookedAt(LocalDateTime.now().minusMinutes(30))
                .build();

        // NEW: lock and transaction are no-ops that just run the work.
        // lenient() because not every test reaches them (e.g. "booking not found").
        lenient().when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
        lenient().when(tx.execute(any())).thenAnswer(inv ->
                inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));

        setUserContext(testUser.getEmail(), "ROLE_USER");
    }

    private void setUserContext(String email, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    private void setAdminContext() {
        var auth = new UsernamePasswordAuthenticationToken(
                "admin@test.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));
    }

    // ── cancel() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("cancels a confirmed booking and restores seats")
        void cancelsConfirmedBookingAndRestoresSeats() {
            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.claimForCancel(eq(1L), any(LocalDateTime.class))).thenReturn(1);

            bookingService.cancel("BK-TEST001");

            verify(bookingRepository).claimForCancel(eq(1L), any(LocalDateTime.class));
            verify(eventRepository).releaseSeats(eq(1L), eq(2), any(LocalDateTime.class));
        }

        // The SOLD_OUT -> UPCOMING flip is now done inside the releaseSeats SQL (a CASE),
        // so a unit test can only prove releaseSeats is called. The status flip itself
        // needs an integration test against a real DB.
        @Test
        @DisplayName("restores seats for a sold-out event too")
        void restoresSeatsForSoldOutEvent() {
            testEvent.setAvailableSeats(0);
            testEvent.setStatus(Status.SOLD_OUT);

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.claimForCancel(eq(1L), any(LocalDateTime.class))).thenReturn(1);

            bookingService.cancel("BK-TEST001");

            verify(eventRepository).releaseSeats(eq(1L), eq(2), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("throws IllegalStateException when booking is already cancelled")
        void throwsWhenAlreadyCancelled() {
            confirmedBooking.setStatus(BookingStatus.CANCELLED);

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));

            assertThatThrownBy(() -> bookingService.cancel("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already cancelled");

            verify(eventRepository, never()).releaseSeats(any(), anyInt(), any());
        }

        // NEW: someone else (the expiry job, or a double click) cancelled it between our
        // read and our claim. The atomic claim returns 0, so we must NOT restore seats.
        @Test
        @DisplayName("does not restore seats when another request already cancelled it")
        void doesNotRestoreSeatsWhenClaimLost() {
            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.claimForCancel(eq(1L), any(LocalDateTime.class))).thenReturn(0);

            assertThatThrownBy(() -> bookingService.cancel("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already cancelled");

            verify(eventRepository, never()).releaseSeats(any(), anyInt(), any());
        }

        @Test
        @DisplayName("throws IllegalStateException when event date is in the past")
        void throwsForPastEvent() {
            testEvent.setEventDate(LocalDateTime.now().minusDays(1));

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));

            assertThatThrownBy(() -> bookingService.cancel("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Past event");

            verify(bookingRepository, never()).claimForCancel(any(), any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when booking not found")
        void throwsWhenBookingNotFound() {
            when(bookingRepository.findByBookingRefWithDetails("BK-NOTFOUND"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.cancel("BK-NOTFOUND"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("admin can cancel any user's booking")
        void adminCanCancelAnyBooking() {
            setAdminContext();

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(bookingRepository.claimForCancel(eq(1L), any(LocalDateTime.class))).thenReturn(1);

            BookingDto.Response response = bookingService.cancel("BK-TEST001");

            assertThat(response).isNotNull();
        }
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("returns booking for the owning user")
        void returnsBookingForOwner() {
            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));

            BookingDto.Response response = bookingService.get("BK-TEST001");

            assertThat(response).isNotNull();
            assertThat(response.getBookingRef()).isEqualTo("BK-TEST001");
        }

        @Test
        @DisplayName("admin can get any booking")
        void adminCanGetAnyBooking() {
            setAdminContext();

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));

            BookingDto.Response response = bookingService.get("BK-TEST001");

            assertThat(response.getBookingRef()).isEqualTo("BK-TEST001");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when booking not found")
        void throwsWhenNotFound() {
            when(bookingRepository.findByBookingRefWithDetails("BK-NOTFOUND"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookingService.get("BK-NOTFOUND"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("BK-NOTFOUND");
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class ListBookings {

        @Test
        @DisplayName("returns own bookings when user requests their own userId")
        void returnsOwnBookings() {
            when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(testUser));
            when(bookingRepository.findByUserIdWithDetails(1L))
                    .thenReturn(List.of(confirmedBooking));

            List<BookingDto.Response> result = bookingService.list(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBookingRef()).isEqualTo("BK-TEST001");
        }

        @Test
        @DisplayName("returns own bookings when userId is null (auto-resolves from auth)")
        void returnsOwnBookingsWhenUserIdNull() {
            when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(testUser));
            when(bookingRepository.findByUserIdWithDetails(1L))
                    .thenReturn(List.of(confirmedBooking));

            List<BookingDto.Response> result = bookingService.list(null);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("admin can list all bookings when userId is null")
        void adminCanListAllBookings() {
            setAdminContext();
            Booking anotherBooking = Booking.builder()
                    .id(2L).bookingRef("BK-TEST002")
                    .user(testUser).event(testEvent)
                    .seatsBooked(1).totalAmount(new BigDecimal("500.00"))
                    .status(BookingStatus.CONFIRMED)
                    .bookedAt(LocalDateTime.now())
                    .build();

            when(bookingRepository.findAllWithDetails())
                    .thenReturn(List.of(confirmedBooking, anotherBooking));

            List<BookingDto.Response> result = bookingService.list(null);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("admin can list bookings filtered by userId")
        void adminCanListBookingsByUserId() {
            setAdminContext();

            when(bookingRepository.findByUserIdWithDetails(1L))
                    .thenReturn(List.of(confirmedBooking));

            List<BookingDto.Response> result = bookingService.list(1L);

            assertThat(result).hasSize(1);
            verify(bookingRepository).findByUserIdWithDetails(1L);
            verify(bookingRepository, never()).findAllWithDetails();
        }

        @Test
        @DisplayName("throws AccessDeniedException when user tries to view another user's bookings")
        void throwsWhenUserAccessesOthersBookings() {
            when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(testUser));

            assertThatThrownBy(() -> bookingService.list(999L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
}