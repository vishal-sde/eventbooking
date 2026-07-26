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


@Component
@Slf4j
@RequiredArgsConstructor
public class BookingExpiryJob {

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;


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