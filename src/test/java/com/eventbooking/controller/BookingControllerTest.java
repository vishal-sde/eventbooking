package com.eventbooking.controller;

import com.eventbooking.config.SecurityConfig;
import com.eventbooking.dto.BookingDto;
import com.eventbooking.entity.BookingStatus;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.exception.SeatsUnavailableException;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(BookingController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-at-least-32-characters-long")
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private UserRepository userRepository;

    private BookingDto.CreateRequest validCreateRequest() {
        return BookingDto.CreateRequest.builder()
                .userId(1L)
                .eventId(1L)
                .seatsRequired(2)
                .build();
    }

    private BookingDto.Response sampleResponse() {
        return BookingDto.Response.builder()
                .id(1L)
                .bookingRef("BK-ABC12345")
                .userId(1L)
                .userName("Jane Doe")
                .eventId(1L)
                .eventName("Coldplay Live")
                .seatsBooked(2)
                .totalAmount(199.98)
                .status(BookingStatus.PENDING)
                .bookedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    // ── POST /api/bookings (create) ─────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/bookings")
    class Create {

        @Test
        @DisplayName("returns 201 when caller is authenticated")
        void createsBooking_whenAuthenticated() throws Exception {
            when(bookingService.create(any(BookingDto.CreateRequest.class))).thenReturn(sampleResponse());

            mockMvc.perform(post("/api/bookings")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingRef").value("BK-ABC12345"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsCreate_whenAnonymous() throws Exception {
            mockMvc.perform(post("/api/bookings")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(bookingService);
        }

        @Test
        @DisplayName("returns 400 when required fields are missing")
        void rejectsCreate_whenInvalidBody() throws Exception {
            BookingDto.CreateRequest invalid = BookingDto.CreateRequest.builder().build();

            mockMvc.perform(post("/api/bookings")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.userId").exists())
                    .andExpect(jsonPath("$.validationErrors.eventId").exists())
                    .andExpect(jsonPath("$.validationErrors.seatsRequired").exists());

            verifyNoInteractions(bookingService);
        }

        @Test
        @DisplayName("returns 409 when not enough seats are available")
        void returns409_whenSeatsUnavailable() throws Exception {
            when(bookingService.create(any(BookingDto.CreateRequest.class)))
                    .thenThrow(new SeatsUnavailableException("Only 1 seats are available"));

            mockMvc.perform(post("/api/bookings")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Only 1 seats are available"));
        }

        @Test
        @DisplayName("returns 403 when caller tries to book on another user's behalf")
        void returns403_whenNotOwner() throws Exception {
            when(bookingService.create(any(BookingDto.CreateRequest.class)))
                    .thenThrow(new AccessDeniedException("You cannot manage another user's booking"));

            mockMvc.perform(post("/api/bookings")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isForbidden());
        }
    }

    // ── POST /api/bookings/{reference}/confirm ──────────────────────────────

    @Nested
    @DisplayName("POST /api/bookings/{reference}/confirm")
    class Confirm {

        @Test
        @DisplayName("returns 200 when caller is authenticated")
        void confirmsBooking_whenAuthenticated() throws Exception {
            BookingDto.Response confirmed = sampleResponse();
            confirmed.setStatus(BookingStatus.CONFIRMED);
            when(bookingService.confirm("BK-ABC12345")).thenReturn(confirmed);

            mockMvc.perform(post("/api/bookings/BK-ABC12345/confirm")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }

        @Test
        @DisplayName("returns 400 when booking has expired")
        void returns400_whenExpired() throws Exception {
            when(bookingService.confirm("BK-ABC12345"))
                    .thenThrow(new IllegalStateException("Booking has expired. Please book again."));

            mockMvc.perform(post("/api/bookings/BK-ABC12345/confirm")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 404 when booking does not exist")
        void returns404_whenMissing() throws Exception {
            when(bookingService.confirm("BK-MISSING"))
                    .thenThrow(new ResourceNotFoundException("Booking not found: BK-MISSING"));

            mockMvc.perform(post("/api/bookings/BK-MISSING/confirm")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ── GET /api/bookings/{reference} ───────────────────────────────────────

    @Nested
    @DisplayName("GET /api/bookings/{reference}")
    class Get {

        @Test
        @DisplayName("returns 200 when caller is authenticated")
        void getsBooking_whenAuthenticated() throws Exception {
            when(bookingService.get("BK-ABC12345")).thenReturn(sampleResponse());

            mockMvc.perform(get("/api/bookings/BK-ABC12345")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingRef").value("BK-ABC12345"));
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsGet_whenAnonymous() throws Exception {
            mockMvc.perform(get("/api/bookings/BK-ABC12345"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(bookingService);
        }
    }

    // ── GET /api/bookings (list) ────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/bookings")
    class ListBookings {

        @Test
        @DisplayName("returns 200 with bookings for the authenticated caller")
        void listsBookings_whenAuthenticated() throws Exception {
            when(bookingService.list(null)).thenReturn(List.of(sampleResponse()));

            mockMvc.perform(get("/api/bookings")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].bookingRef").value("BK-ABC12345"));
        }

        @Test
        @DisplayName("returns 403 when a non-admin requests another user's bookings")
        void returns403_whenViewingAnotherUsersBookings() throws Exception {
            when(bookingService.list(eq(2L)))
                    .thenThrow(new AccessDeniedException("You cannot view another user's bookings"));

            mockMvc.perform(get("/api/bookings").param("userId", "2")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());
        }
    }

    // ── DELETE /api/bookings/{reference} (cancel) ───────────────────────────

    @Nested
    @DisplayName("DELETE /api/bookings/{reference}")
    class Cancel {

        @Test
        @DisplayName("returns 200 when caller is authenticated")
        void cancelsBooking_whenAuthenticated() throws Exception {
            BookingDto.Response cancelled = sampleResponse();
            cancelled.setStatus(BookingStatus.CANCELLED);
            when(bookingService.cancel("BK-ABC12345")).thenReturn(cancelled);

            mockMvc.perform(delete("/api/bookings/BK-ABC12345")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsCancel_whenAnonymous() throws Exception {
            mockMvc.perform(delete("/api/bookings/BK-ABC12345"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(bookingService);
        }

        @Test
        @DisplayName("returns 400 when booking is already cancelled")
        void returns400_whenAlreadyCancelled() throws Exception {
            when(bookingService.cancel("BK-ABC12345"))
                    .thenThrow(new IllegalStateException("Booking is already cancelled"));

            mockMvc.perform(delete("/api/bookings/BK-ABC12345")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isBadRequest());
        }
    }
}