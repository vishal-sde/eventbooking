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
                .ticketPrice(500.0)
                .status(Status.UPCOMING)
                .build();

        confirmedBooking = Booking.builder()
                .id(1L)
                .bookingRef("BK-TEST001")
                .user(testUser)
                .event(testEvent)
                .seatsBooked(2)
                .totalAmount(1000.0)
                .status(BookingStatus.CONFIRMED)
                .bookedAt(LocalDateTime.now().minusMinutes(30))
                .build();

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
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.save(any(Booking.class))).thenReturn(confirmedBooking);
            when(eventRepository.save(any(Event.class))).thenReturn(testEvent);

            bookingService.cancel("BK-TEST001");

            verify(bookingRepository).save(argThat(b ->
                    b.getStatus() == BookingStatus.CANCELLED &&
                            b.getCancelledAt() != null
            ));
            verify(eventRepository).save(argThat(e ->
                    e.getAvailableSeats() == 92  // 90 + 2
            ));
        }

        @Test
        @DisplayName("changes SOLD_OUT event back to UPCOMING on cancellation")
        void changesSoldOutToUpcomingOnCancel() {
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
            testEvent.setAvailableSeats(0);
            testEvent.setStatus(Status.SOLD_OUT);

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.save(any())).thenReturn(confirmedBooking);
            when(eventRepository.save(any())).thenReturn(testEvent);

            bookingService.cancel("BK-TEST001");

            verify(eventRepository).save(argThat(e ->
                    e.getStatus() == Status.UPCOMING
            ));
        }

        @Test
        @DisplayName("throws IllegalStateException when booking is already cancelled")
        void throwsWhenAlreadyCancelled() {
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
            confirmedBooking.setStatus(BookingStatus.CANCELLED);

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));

            assertThatThrownBy(() -> bookingService.cancel("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already cancelled");
        }

        @Test
        @DisplayName("throws IllegalStateException when event date is in the past")
        void throwsForPastEvent() {
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
            testEvent.setEventDate(LocalDateTime.now().minusDays(1));

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));

            assertThatThrownBy(() -> bookingService.cancel("BK-TEST001"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Past event");
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
            when(lockService.executeWithLock(anyLong(), any(Supplier.class)))
                    .thenAnswer(inv -> inv.<Supplier<?>>getArgument(1).get());
            setAdminContext();

            when(bookingRepository.findByBookingRefWithDetails("BK-TEST001"))
                    .thenReturn(Optional.of(confirmedBooking));
            when(eventRepository.findById(1L)).thenReturn(Optional.of(testEvent));
            when(bookingRepository.save(any())).thenReturn(confirmedBooking);
            when(eventRepository.save(any())).thenReturn(testEvent);

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
                    .seatsBooked(1).totalAmount(500.0)
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

            // userId 999 != testUser.id (1)
            assertThatThrownBy(() -> bookingService.list(999L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }
}