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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;


@Component
@Slf4j
@RequiredArgsConstructor
public class BookingExpiryJob {

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final TransactionTemplate tx;


    @Scheduled(fixedDelay = 60000)
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
                tx.execute(status -> { releaseBooking(booking,now); return null; });
            } catch (Exception e) {
                log.error("Failed to release expired booking {}: {}",
                        booking.getBookingRef(), e.getMessage());
            }
        }
    }

    private void releaseBooking(Booking booking,LocalDateTime now) {
        if (bookingRepository.claimForExpiry(booking.getId(),now)==0){
            return;
        }
        eventRepository.releaseSeats(booking.getEvent().getId(), booking.getSeatsBooked(),now);
        log.info("Released expired booking {} — {} seat(s) restored",
                booking.getBookingRef(), booking.getSeatsBooked());
    }
}