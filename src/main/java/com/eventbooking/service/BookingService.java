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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
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
    private final EmailService emailService;
    private final TransactionTemplate tx;


    public BookingDto.Response create(BookingDto.CreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + request.getUserId()));
        requireOwnerOrAdmin(user);


        return lockService.executeWithLock(request.getEventId(),
                () -> tx.execute(status ->  processCreate(request, user)));
    }


    @Transactional
    protected BookingDto.Response processCreate(BookingDto.CreateRequest request, User user) {
        LocalDateTime now = LocalDateTime.now();
        Event event = eventRepository.findById(request.getEventId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found: " + request.getEventId()));

        if(event.getStatus()==Status.SOLD_OUT){
            throw new SeatsUnavailableException("Event is sold out");
        }
        if (event.getStatus() != Status.UPCOMING ||
                !event.getEventDate().isAfter(now)) {
            throw new IllegalStateException("This event is not open for booking");
        }
        if(bookingRepository.existsByUserIdAndEventIdAndStatusIn(
                user.getId(),event.getId(),List.of(BookingStatus.PENDING,BookingStatus.CONFIRMED))){
            throw new DuplicateBookingException("You already have an active booking for this event");
        }
        if (bookingRepository.findByUserIdAndEventIdAndStatus(
                user.getId(), event.getId(), BookingStatus.CONFIRMED).isPresent()) {
            throw new DuplicateBookingException(
                    "You already have a confirmed booking for this event");
        }
        if(eventRepository.reserveSeats(event.getId(),request.getSeatsRequired(),now)==0){
            throw new SeatsUnavailableException("Not enough seats available");
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
                .totalAmount(event.getTicketPrice().multiply(BigDecimal.valueOf(request.getSeatsRequired())))
                .status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();

        Booking saved = bookingRepository.save(booking);
        eventRepository.markSoldOutIfEmpty(event.getId(),now);
        log.info("Booking confirmed: {} event: {} seats: {}",
                saved.getBookingRef(), event.getName(), request.getSeatsRequired());
        return BookingDto.Response.from(saved);
    }

    public BookingDto.Response cancel(String reference) {
        Booking authCheck = bookingRepository.findByBookingRefWithDetails(reference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Booking not found: " + reference));
        requireOwnerOrAdmin(authCheck.getUser());
        Long eventId = authCheck.getEvent().getId();


        return lockService.executeWithLock(eventId, () -> tx.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            Booking booking = bookingRepository.findByBookingRefWithDetails(reference)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Booking not found: " + reference));

            if (booking.getStatus() == BookingStatus.CANCELLED) {
                throw new IllegalStateException("Booking is already cancelled");
            }
            if(!booking.getEvent().getEventDate().isAfter(now)){
                throw new IllegalStateException("Past event bookings cannot be cancelled");
            }
            if(bookingRepository.claimForCancel(booking.getId(),now)==0){
                throw new IllegalStateException("Booking is already cancelled");
            }
            eventRepository.releaseSeats(eventId,booking.getSeatsBooked(),now);
            log.info("Booking cancelled: {} seats restored: {}",reference,booking.getSeatsBooked());
            return BookingDto.Response.from(bookingRepository.findByBookingRefWithDetails(reference).orElseThrow());
        }));
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
        Booking saved = bookingRepository.save(booking);
        emailService.sendBookingConfirmation(saved);
        return BookingDto.Response.from(saved);
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