package com.eventbooking.service;

import com.eventbooking.dto.EventDto;
import com.eventbooking.dto.PagedResponse;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private EventService eventService;

    // Reusable test data
    private Event upcomingEvent;
    private Event cancelledEvent;

    @BeforeEach
    void setUp() {
        upcomingEvent = Event.builder()
                .id(1L)
                .name("Test Concert")
                .venue("Test Arena")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(100)
                .availableSeats(100)
                .ticketPrice(500.0)
                .status(Status.UPCOMING)
                .build();

        cancelledEvent = Event.builder()
                .id(2L)
                .name("Cancelled Show")
                .venue("Some Venue")
                .eventDate(LocalDateTime.now().plusDays(10))
                .totalSeats(50)
                .availableSeats(50)
                .ticketPrice(200.0)
                .status(Status.CANCELLED)
                .build();
    }

    // ── create() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("creates event with correct initial state")
        void createsEventSuccessfully() {
            EventDto.CreateRequest request = EventDto.CreateRequest.builder()
                    .name("New Concert")
                    .venue("Stadium")
                    .eventDate(LocalDateTime.now().plusDays(30))
                    .totalSeats(200)
                    .ticketPrice(750.0)
                    .build();

            // Tell mock: when save() is called with any Event, return upcomingEvent
            when(eventRepository.save(any(Event.class))).thenReturn(upcomingEvent);

            EventDto.Response response = eventService.create(request);

            // Verify the response
            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(Status.UPCOMING);

            // Verify save() was called exactly once
            verify(eventRepository, times(1)).save(any(Event.class));
        }

        @Test
        @DisplayName("sets availableSeats equal to totalSeats on creation")
        void setsAvailableSeatsEqualToTotalSeats() {
            EventDto.CreateRequest request = EventDto.CreateRequest.builder()
                    .name("Concert")
                    .venue("Arena")
                    .eventDate(LocalDateTime.now().plusDays(30))
                    .totalSeats(100)
                    .ticketPrice(500.0)
                    .build();

            when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
                // Return the actual event passed to save() so we can inspect it
                Event saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            EventDto.Response response = eventService.create(request);

            // availableSeats must equal totalSeats at creation
            assertThat(response.getAvailableSeats())
                    .isEqualTo(response.getTotalSeats());
        }

        @Test
        @DisplayName("trims whitespace from name and venue")
        void trimsWhitespace() {
            EventDto.CreateRequest request = EventDto.CreateRequest.builder()
                    .name("  Concert With Spaces  ")
                    .venue("  Arena  ")
                    .eventDate(LocalDateTime.now().plusDays(30))
                    .totalSeats(100)
                    .ticketPrice(500.0)
                    .build();

            when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
                Event saved = invocation.getArgument(0);
                saved.setId(1L);
                return saved;
            });

            EventDto.Response response = eventService.create(request);

            assertThat(response.getName()).isEqualTo("Concert With Spaces");
            assertThat(response.getVenue()).isEqualTo("Arena");
        }
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("returns event when found")
        void returnsEventWhenFound() {
            when(eventRepository.findById(1L)).thenReturn(Optional.of(upcomingEvent));

            EventDto.Response response = eventService.get(1L);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getName()).isEqualTo("Test Concert");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when event not found")
        void throwsWhenNotFound() {
            when(eventRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> eventService.get(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
        }
    }

    // ── search() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("search()")
    class Search {

        @Test
        @DisplayName("returns paginated results")
        void returnsPaginatedResults() {
            var pageResult = new PageImpl<>(List.of(upcomingEvent));
            when(eventRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(pageResult);

            PagedResponse<EventDto.Response> response =
                    eventService.search(null, null, null, null, null, null, null, null, 0, 10, "eventDate", "asc");

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("passes null for blank search term")
        void passesNullForBlankSearch() {
            var pageResult = new PageImpl<>(List.of(upcomingEvent));
            when(eventRepository.search(isNull(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(pageResult);

            // Blank string should be treated as null so the query ignores it
            PagedResponse<EventDto.Response> response =
                    eventService.search("   ", null, null, null, null, null, null, null, 0, 10, "eventDate", "asc");

            assertThat(response.getContent()).hasSize(1);
            // Verify null was passed for search, not the blank string
            verify(eventRepository).search(isNull(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("defaults to eventDate sort for invalid sort field")
        void defaultsToEventDateForInvalidSortField() {
            var pageResult = new PageImpl<>(List.of(upcomingEvent));
            when(eventRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(pageResult);

            // "hackedField" is not in the allowed list — should default to eventDate
            assertThatNoException().isThrownBy(() ->
                    eventService.search(null, null, null, null, null, null, null, null, 0, 10, "hackedField", "asc")
            );
        }

        @Test
        @DisplayName("caps page size at 50")
        void capsPageSizeAt50() {
            var pageResult = new PageImpl<>(List.of(upcomingEvent));
            when(eventRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                    .thenReturn(pageResult);

            // Request size 1000 — should be capped at 50
            eventService.search(null, null, null, null, null, null, null, null, 0, 1000, "eventDate", "asc");

            verify(eventRepository).search(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    argThat(pageable -> pageable.getPageSize() == 50)
            );
        }
    }

    // ── cancel() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("cancels an upcoming event")
        void cancelsUpcomingEvent() {
            when(eventRepository.findByIdForUpdate(1L))
                    .thenReturn(Optional.of(upcomingEvent));
            when(eventRepository.save(any(Event.class))).thenReturn(upcomingEvent);
            when(bookingRepository.findByEventIdAndStatus(any(), any()))
                    .thenReturn(List.of());

            EventDto.Response response = eventService.cancel(1L);

            assertThat(response).isNotNull();
            verify(eventRepository).save(argThat(e -> e.getStatus() == Status.CANCELLED));
        }

        @Test
        @DisplayName("throws when trying to cancel a completed event")
        void throwsWhenCancellingCompletedEvent() {
            Event completedEvent = Event.builder()
                    .id(3L)
                    .status(Status.COMPLETED)
                    .name("Done Event")
                    .venue("Venue")
                    .eventDate(LocalDateTime.now().minusDays(1))
                    .totalSeats(100)
                    .availableSeats(0)
                    .ticketPrice(100.0)
                    .build();

            when(eventRepository.findByIdForUpdate(3L))
                    .thenReturn(Optional.of(completedEvent));

            assertThatThrownBy(() -> eventService.cancel(3L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException for unknown event")
        void throwsForUnknownEvent() {
            when(eventRepository.findByIdForUpdate(99L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> eventService.cancel(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── update() ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("updates upcoming event successfully")
        void updatesUpcomingEvent() {
            EventDto.UpdateRequest request = EventDto.UpdateRequest.builder()
                    .name("Updated Name")
                    .venue("Updated Venue")
                    .eventDate(LocalDateTime.now().plusDays(60))
                    .ticketPrice(999.0)
                    .build();

            when(eventRepository.findById(1L)).thenReturn(Optional.of(upcomingEvent));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

            EventDto.Response response = eventService.update(1L, request);

            assertThat(response.getName()).isEqualTo("Updated Name");
            assertThat(response.getVenue()).isEqualTo("Updated Venue");
        }

        @Test
        @DisplayName("throws when updating a non-upcoming event")
        void throwsWhenUpdatingNonUpcomingEvent() {
            EventDto.UpdateRequest request = EventDto.UpdateRequest.builder()
                    .name("New Name")
                    .venue("New Venue")
                    .eventDate(LocalDateTime.now().plusDays(10))
                    .ticketPrice(100.0)
                    .build();

            when(eventRepository.findById(2L)).thenReturn(Optional.of(cancelledEvent));

            assertThatThrownBy(() -> eventService.update(2L, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("upcoming");
        }
    }
}