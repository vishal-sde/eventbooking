package com.eventbooking.controller;

import com.eventbooking.config.SecurityConfig;
import com.eventbooking.dto.EventDto;
import com.eventbooking.dto.PagedResponse;
import com.eventbooking.entity.Status;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.EventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-at-least-32-characters-long")
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private UserRepository userRepository;

    private EventDto.CreateRequest validCreateRequest() {
        return EventDto.CreateRequest.builder()
                .name("Coldplay Live")
                .venue("Wembley Stadium")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(500)
                .ticketPrice(99.99)
                .build();
    }

    private EventDto.Response sampleResponse() {
        return EventDto.Response.builder()
                .id(1L)
                .name("Coldplay Live")
                .venue("Wembley Stadium")
                .eventDate(LocalDateTime.now().plusDays(30))
                .totalSeats(500)
                .availableSeats(500)
                .ticketPrice(99.99)
                .status(Status.UPCOMING)
                .version(0L)
                .build();
    }

    // ── POST /api/events (create) ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/events")
    class Create {

        @Test
        @DisplayName("returns 201 when caller has ROLE_ADMIN")
        void createsEvent_asAdmin() throws Exception {
            EventDto.CreateRequest createRequest = validCreateRequest();
            when(eventService.create(any(EventDto.CreateRequest.class))).thenReturn(sampleResponse());

            mockMvc.perform(post("/api/events")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(createRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(1L))
                    .andExpect(jsonPath("$.name").value("Coldplay Live"))
                    .andExpect(jsonPath("$.status").value("UPCOMING"));

            verify(eventService).create(any(EventDto.CreateRequest.class));
        }

        @Test
        @DisplayName("returns 403 when caller has ROLE_USER")
        void rejectsCreate_asNonAdmin() throws Exception {
            mockMvc.perform(post("/api/events")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(eventService);
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsCreate_whenAnonymous() throws Exception {
            mockMvc.perform(post("/api/events")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(eventService);
        }

        @Test
        @DisplayName("returns 400 when required fields are missing")
        void rejectsCreate_whenInvalidBody() throws Exception {
            EventDto.CreateRequest invalid = EventDto.CreateRequest.builder()
                    .name("")
                    .venue("")
                    .totalSeats(null)
                    .ticketPrice(null)
                    .build();

            mockMvc.perform(post("/api/events")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.name").exists())
                    .andExpect(jsonPath("$.validationErrors.venue").exists());

            verifyNoInteractions(eventService);
        }
    }

    // ── GET /api/events/{id} ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/events/{id}")
    class Get {

        @Test
        @DisplayName("returns 200 without authentication (public endpoint)")
        void getsEvent_whenPublic() throws Exception {
            when(eventService.get(1L)).thenReturn(sampleResponse());

            mockMvc.perform(get("/api/events/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1L));
        }

        @Test
        @DisplayName("returns 404 when event does not exist")
        void returns404_whenMissing() throws Exception {
            when(eventService.get(99L)).thenThrow(new ResourceNotFoundException("Event not found: 99"));

            mockMvc.perform(get("/api/events/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Event not found: 99"));
        }
    }

    // ── GET /api/events (search) ────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/events")
    class Search {

        @Test
        @DisplayName("returns 200 with paged results")
        void searchesEvents() throws Exception {
            PagedResponse<EventDto.Response> paged = PagedResponse.<EventDto.Response>builder()
                    .content(List.of(sampleResponse()))
                    .page(0)
                    .size(10)
                    .totalElements(1)
                    .totalPages(1)
                    .first(true)
                    .last(true)
                    .build();
            when(eventService.search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(10), eq("eventDate"), eq("asc")))
                    .thenReturn(paged);

            mockMvc.perform(get("/api/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(1L))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("returns 400 when minSeats is below 1")
        void rejectsSearch_whenMinSeatsInvalid() throws Exception {
            mockMvc.perform(get("/api/events").param("minSeats", "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(eventService);
        }
    }

    // ── PUT /api/events/{id} ────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/events/{id}")
    class Update {

        @Test
        @DisplayName("returns 200 when caller has ROLE_ADMIN")
        void updatesEvent_asAdmin() throws Exception {
            EventDto.UpdateRequest updateRequest = EventDto.UpdateRequest.builder()
                    .name("Updated Name")
                    .venue("Updated Venue")
                    .eventDate(LocalDateTime.now().plusDays(60))
                    .ticketPrice(150.0)
                    .build();
            when(eventService.update(eq(1L), any(EventDto.UpdateRequest.class))).thenReturn(sampleResponse());

            mockMvc.perform(put("/api/events/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns 403 when caller has ROLE_USER")
        void rejectsUpdate_asNonAdmin() throws Exception {
            EventDto.UpdateRequest updateRequest = EventDto.UpdateRequest.builder()
                    .name("Updated Name")
                    .venue("Updated Venue")
                    .eventDate(LocalDateTime.now().plusDays(60))
                    .ticketPrice(150.0)
                    .build();

            mockMvc.perform(put("/api/events/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(eventService);
        }

        @Test
        @DisplayName("returns 400 when event is not upcoming")
        void returns400_whenEventNotUpcoming() throws Exception {
            EventDto.UpdateRequest updateRequest = EventDto.UpdateRequest.builder()
                    .name("Updated Name")
                    .venue("Updated Venue")
                    .eventDate(LocalDateTime.now().plusDays(60))
                    .ticketPrice(150.0)
                    .build();
            when(eventService.update(eq(1L), any(EventDto.UpdateRequest.class)))
                    .thenThrow(new IllegalStateException("Only upcoming events can be updated"));

            mockMvc.perform(put("/api/events/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(updateRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Only upcoming events can be updated"));
        }
    }

    // ── DELETE /api/events/{id} (cancel) ────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/events/{id}")
    class Cancel {

        @Test
        @DisplayName("returns 200 when caller has ROLE_ADMIN")
        void cancelsEvent_asAdmin() throws Exception {
            EventDto.Response cancelled = sampleResponse();
            cancelled.setStatus(Status.CANCELLED);
            when(eventService.cancel(1L)).thenReturn(cancelled);

            mockMvc.perform(delete("/api/events/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("returns 403 when caller has ROLE_USER")
        void rejectsCancel_asNonAdmin() throws Exception {
            mockMvc.perform(delete("/api/events/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(eventService);
        }
    }
}