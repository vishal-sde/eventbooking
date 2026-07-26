package com.eventbooking.service;

import com.eventbooking.dto.EventDto;
import com.eventbooking.entity.Category;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.BookingStatus;
import com.eventbooking.entity.Status;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.eventbooking.dto.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {
    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public EventDto.Response create(EventDto.CreateRequest request) {
        Event event = Event.builder()
                .name(request.getName().trim())
                .venue(request.getVenue().trim())
                .city(request.getCity() != null ? request.getCity().trim() : null)
                .eventDate(request.getEventDate())
                .totalSeats(request.getTotalSeats())
                .availableSeats(request.getTotalSeats())
                .ticketPrice(request.getTicketPrice())
                .status(Status.UPCOMING)
                .category(request.getCategory() != null ? request.getCategory() : Category.OTHER)
                .imageUrl(request.getImageUrl())
                .description(request.getDescription())
                .build();
        return EventDto.Response.from(eventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public EventDto.Response get(Long id) {
        return EventDto.Response.from(findEvent(id));
    }

    @Transactional(readOnly = true)
    public PagedResponse<EventDto.Response> search(
            String searchTerm,
            Status status,
            Integer minSeats,
            Category category,
            String city,
            Double minPrice,
            Double maxPrice,
            LocalDateTime dateFrom,
            int page,
            int size,
            String sortBy,
            String sortDir) {

        // Validate sort field — never trust client input for column names
        List<String> allowedSortFields = List.of("eventDate", "name", "ticketPrice", "availableSeats");
        if (!allowedSortFields.contains(sortBy)) {
            sortBy = "eventDate"; // default
        }

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, Math.min(size, 50), sort);

        // Pass null for empty strings so the query ignores that filter
        String search = (searchTerm == null || searchTerm.isBlank()) ? null : searchTerm.trim();
        String cityFilter = (city == null || city.isBlank()) ? null : city.trim();

        Page<EventDto.Response> results = eventRepository
                .search(search, status, minSeats, category, cityFilter, minPrice, maxPrice, dateFrom, pageable)
                .map(EventDto.Response::from);

        return PagedResponse.from(results);
    }

    @Transactional
    public EventDto.Response update(Long id, EventDto.UpdateRequest request) {
        Event event = findEvent(id);
        if (event.getStatus() != Status.UPCOMING) {
            throw new IllegalStateException("Only upcoming events can be updated");
        }
        event.setName(request.getName().trim());
        event.setVenue(request.getVenue().trim());
        event.setCity(request.getCity() != null ? request.getCity().trim() : null);
        event.setEventDate(request.getEventDate());
        event.setTicketPrice(request.getTicketPrice());
        if (request.getCategory() != null) {
            event.setCategory(request.getCategory());
        }
        event.setImageUrl(request.getImageUrl());
        event.setDescription(request.getDescription());
        return EventDto.Response.from(eventRepository.save(event));
    }

    @Transactional
    public EventDto.Response cancel(Long id) {
        Event event = eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
        if (event.getStatus() == Status.COMPLETED) {
            throw new IllegalStateException("A completed event cannot be cancelled");
        }
        event.setStatus(Status.CANCELLED);
        bookingRepository.findByEventIdAndStatus(id, BookingStatus.CONFIRMED).forEach(booking -> {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(java.time.LocalDateTime.now());
        });
        return EventDto.Response.from(eventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public Category[] categories() {
        return Category.values();
    }

    private Event findEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
    }
}