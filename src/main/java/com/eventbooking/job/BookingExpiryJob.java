package com.eventbooking.job;

import com.eventbooking.entity.Booking;
import com.eventbooking.entity.BookingStatus;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BookingExpiryJob
 *
 * Runs every 60 seconds. Finds PENDING bookings whose expiresAt
 * timestamp has passed and releases their seats back to the event.
 *
 * WHY THIS EXISTS:
 * In a real ticketing system, booking has two phases:
 * 1. PENDING  — seat is held, awaiting payment (5 minute window)
 * 2. CONFIRMED — payment received, seat is yours
 *
 * Without expiry, a user could hold seats indefinitely without paying.
 * This job is the cleanup mechanism that prevents seat hoarding.
 *
 * WHY @Scheduled INSTEAD OF Redis TTL:
 * Redis TTL would expire the key automatically but you'd still need
 * a job to restore seats in the DB when that happens. The scheduled
 * job approach is simpler, easier to monitor, and doesn't require
 * Redis keyspace notifications. For most systems this is sufficient.
 *
 * INTERVIEW TALKING POINT:
 * "I used a scheduled job that runs every 60 seconds to release
 * expired holds. In a higher-scale system I'd move to event-driven
 * expiry using Redis keyspace notifications or a message queue,
 * but for this use case a polling approach is pragmatic and
 * operationally simpler."
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BookingExpiryJob {

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;

    /**
     * fixedDelay = 60000ms = runs every 60 seconds AFTER the previous run completes.
     * Using fixedDelay instead of fixedRate prevents overlap if a run takes longer than 60s.
     *
     * For production you'd use a cron expression:
     * @Scheduled(cron = "0 * * * * *") — runs at the start of every minute
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void releaseExpiredBookings() {
        LocalDateTime now = LocalDateTime.now();
        List<Booking> expired = bookingRepository.findExpiredPendingBookings(now);

        if (expired.isEmpty()) {
            log.debug("Expiry job: no expired bookings found");
            return;
        }

        log.info("Expiry job: found {} expired pending bookings to release", expired.size());

        for (Booking booking : expired) {
            try {
                releaseBooking(booking);
            } catch (Exception e) {
                // Don't let one failed release kill the entire batch
                // Log it and continue with the rest
                log.error("Failed to release expired booking {}: {}",
                        booking.getBookingRef(), e.getMessage());
            }
        }
    }

    private void releaseBooking(Booking booking) {
        Event event = booking.getEvent();

        // Mark booking as cancelled
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());

        // Restore seats — cap at totalSeats to prevent overflow bugs
        int restored = Math.min(
                event.getTotalSeats(),
                event.getAvailableSeats() + booking.getSeatsBooked()
        );
        event.setAvailableSeats(restored);

        // If event was SOLD_OUT, it now has seats again
        if (event.getStatus() == Status.SOLD_OUT) {
            event.setStatus(Status.UPCOMING);
        }

        bookingRepository.save(booking);
        eventRepository.save(event);

        log.info("Released expired booking {} — {} seat(s) restored to event '{}'",
                booking.getBookingRef(), booking.getSeatsBooked(), event.getName());
    }
}