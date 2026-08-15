package com.eventbooking.job;

import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import com.eventbooking.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Flips events whose eventDate has passed from UPCOMING/SOLD_OUT to COMPLETED.
 *
 * Without this job, an event that has already happened keeps showing as
 * "Upcoming" in every listing and search query, and there is no automated
 * way to get it out of the active list — it just sits there occupying
 * space until an admin manually cancels it (which also wrongly refunds
 * confirmed bookings for an event that already took place).
 *
 * Runs on the same fixedDelay cadence as BookingExpiryJob so both
 * housekeeping jobs stay in sync with each other.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EventCompletionJob {

    private final EventRepository eventRepository;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void completePastEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<Event> pastEvents = eventRepository.findPastEventsStillOpen(now);

        if (pastEvents.isEmpty()) {
            log.debug("Event completion job: no past events still open");
            return;
        }

        log.info("Event completion job: marking {} past event(s) as COMPLETED", pastEvents.size());

        for (Event event : pastEvents) {
            event.setStatus(Status.COMPLETED);
        }
        eventRepository.saveAll(pastEvents);
    }
}