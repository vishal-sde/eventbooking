package com.eventbooking.service;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.entity.*;
import com.eventbooking.exception.DuplicateBookingException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.exception.SeatsUnavailableException;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final DistributedLockService lockService;

    /**
     * NO @Transactional here — intentional.
     * Lock must be acquired BEFORE the transaction opens.
     * If @Transactional were here, the DB read would happen before
     * the lock is held — race condition window exists.
     */
    public BookingDto.Response create(BookingDto.CreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + request.getUserId()));
        requireOwnerOrAdmin(user);

        // Lock acquired here — BEFORE transaction starts
        return lockService.executeWithLock(
                request.getEventId(),
                () -> processCreate(request, user)
        );
    }

    /**
     * @Transactional IS here — inside the lock.
     * @Version on Event is the safety net:
     * if two threads somehow both pass the Redis lock,
     * only one succeeds writing. The other gets
     * ObjectOptimisticLockingFailureException → 409 to client.
     */
    @Transactional
    protected BookingDto.Response processCreate(BookingDto.CreateRequest request, User user) {
        // Fresh read inside transaction — guaranteed to see latest data
        // because we hold the Redis lock and no other thread can write
        Event event = eventRepository.findById(request.getEventId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found: " + request.getEventId()));

        if (event.getStatus() != Status.UPCOMING ||
                !event.getEventDate().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("This event is not open for booking");
        }
        if (bookingRepository.findByUserIdAndEventIdAndStatus(
                user.getId(), event.getId(), BookingStatus.CONFIRMED).isPresent()) {
            throw new DuplicateBookingException(
                    "You already have a confirmed booking for this event");
        }
        if (event.getAvailableSeats() < request.getSeatsRequired()) {
            throw new SeatsUnavailableException(
                    "Only " + event.getAvailableSeats() + " seats are available");
        }

        event.setAvailableSeats(event.getAvailableSeats() - request.getSeatsRequired());
        if (event.getAvailableSeats() == 0) {
            event.setStatus(Status.SOLD_OUT);
        }

        Booking booking = Booking.builder()
                .user(user)
                .event(event)
                .seatsBooked(request.getSeatsRequired())
                .totalAmount(event.getTicketPrice() * request.getSeatsRequired())
                .status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();

        eventRepository.save(event); // @Version check fires here
        BookingDto.Response response = BookingDto.Response.from(bookingRepository.save(booking));
        log.info("Booking confirmed: {} event: {} seats: {}",
                booking.getBookingRef(), event.getName(), request.getSeatsRequired());
        return response;
    }

    public BookingDto.Response cancel(String reference) {
        // First load — for auth check only. User is eagerly loaded.
        Booking authCheck = bookingRepository.findByBookingRefWithDetails(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found: " + reference));
        requireOwnerOrAdmin(authCheck.getUser());

        Long eventId = authCheck.getEvent().getId();

        // Lock wraps the write — same pattern as create()
        return lockService.executeWithLock(eventId, () -> {
            // Second load inside the lock — fresh state, active session
            Booking booking = bookingRepository.findByBookingRefWithDetails(reference)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Booking not found: " + reference));

            if (booking.getStatus() == BookingStatus.CANCELLED) {
                throw new IllegalStateException("Booking is already cancelled");
            }

            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Event no longer exists"));

            if (!event.getEventDate().isAfter(LocalDateTime.now())) {
                throw new IllegalStateException(
                        "Past event bookings cannot be cancelled");
            }

            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(LocalDateTime.now());
            event.setAvailableSeats(Math.min(
                    event.getTotalSeats(),
                    event.getAvailableSeats() + booking.getSeatsBooked()));
            if (event.getStatus() == Status.SOLD_OUT) {
                event.setStatus(Status.UPCOMING);
            }

            eventRepository.save(event);
            log.info("Booking cancelled: {} seats restored: {}",
                    reference, booking.getSeatsBooked());
            return BookingDto.Response.from(bookingRepository.save(booking));
        });
    }



    @Transactional(readOnly = true)
    public BookingDto.Response get(String reference) {
        Booking booking = bookingRepository.findByBookingRefWithDetails(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found: " + reference));
        requireOwnerOrAdmin(booking.getUser());
        return BookingDto.Response.from(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingDto.Response> list(Long userId) {
        Authentication auth = authentication();
        boolean admin = hasAdminRole(auth);
        Long effectiveUserId = userId;
        if (!admin) {
            User currentUser = userRepository.findByEmail(auth.getName().toLowerCase())
                    .orElseThrow(() -> new AccessDeniedException(
                            "Authenticated user no longer exists"));
            if (userId != null && !userId.equals(currentUser.getId())) {
                throw new AccessDeniedException("You cannot view another user's bookings");
            }
            effectiveUserId = currentUser.getId();
        }
        List<Booking> bookings = effectiveUserId == null
                ? bookingRepository.findAllWithDetails()
                : bookingRepository.findByUserIdWithDetails(effectiveUserId);
        return bookings.stream().map(BookingDto.Response::from).toList();
    }

    @Transactional
    public BookingDto.Response confirm(String bookingRef) {
        Booking booking = bookingRepository.findByBookingRefWithDetails(bookingRef)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found: " + bookingRef));
        requireOwnerOrAdmin(booking.getUser());

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Booking has been cancelled");
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Booking is already confirmed");
        }
        if (LocalDateTime.now().isAfter(booking.getExpiresAt())) {
            throw new IllegalStateException(
                    "Booking has expired. Please book again.");
        }

        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setExpiresAt(null); // clear expiry once confirmed
        log.info("Booking confirmed: {}", bookingRef);
        return BookingDto.Response.from(bookingRepository.save(booking));
    }


    @Transactional
    BookingDto.Response createForTest(BookingDto.CreateRequest request, User user) {
        return lockService.executeWithLock(
                request.getEventId(),
                () -> processCreate(request, user)
        );
    }

    private void requireOwnerOrAdmin(User user) {
        Authentication auth = authentication();
        if (!hasAdminRole(auth) &&
                !auth.getName().equalsIgnoreCase(user.getEmail())) {
            throw new AccessDeniedException("You cannot manage another user's booking");
        }
    }

    private Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        return auth;
    }

    private boolean hasAdminRole(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}