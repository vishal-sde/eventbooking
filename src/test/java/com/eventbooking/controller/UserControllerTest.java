package com.eventbooking.controller;

import com.eventbooking.config.SecurityConfig;
import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.Role;
import com.eventbooking.exception.DuplicateResourceException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.UserService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-at-least-32-characters-long")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    private UserDto.CreateRequest validCreateRequest() {
        return UserDto.CreateRequest.builder()
                .name("Jane Doe")
                .email("jane@example.com")
                .phone("9876543210")
                .password("password123")
                .build();
    }

    private UserDto.Response sampleResponse() {
        return UserDto.Response.builder()
                .id(1L)
                .name("Jane Doe")
                .email("jane@example.com")
                .phone("9876543210")
                .role(Role.USER)
                .build();
    }

    // ── POST /api/users (registration) ──────────────────────────────────────

    @Nested
    @DisplayName("POST /api/users")
    class Create {

        @Test
        @DisplayName("returns 202 without authentication (public registration, pending verification)")
        void createsUser_whenAnonymous() throws Exception {
            mockMvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isAccepted());

            verify(userService).create(any(UserDto.CreateRequest.class));
        }

        @Test
        @DisplayName("returns 400 when email is invalid")
        void rejectsCreate_whenEmailInvalid() throws Exception {
            UserDto.CreateRequest invalid = UserDto.CreateRequest.builder()
                    .name("Jane Doe")
                    .email("not-an-email")
                    .phone("9876543210")
                    .password("password123")
                    .build();

            mockMvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.email").exists());

            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("returns 409 when email is already registered")
        void rejectsCreate_whenEmailTaken() throws Exception {
            doThrow(new DuplicateResourceException("A user with this email already exists"))
                    .when(userService).create(any(UserDto.CreateRequest.class));

            mockMvc.perform(post("/api/users")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(validCreateRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("A user with this email already exists"));
        }
    }

    // ── GET /api/users/{id} ──────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/users/{id}")
    class Get {

        @Test
        @DisplayName("returns 200 when caller has ROLE_ADMIN")
        void getsUser_asAdmin() throws Exception {
            when(userService.get(1L)).thenReturn(sampleResponse());

            mockMvc.perform(get("/api/users/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1L));
        }

        @Test
        @DisplayName("returns 403 when caller has ROLE_USER")
        void rejectsGet_asNonAdmin() throws Exception {
            mockMvc.perform(get("/api/users/1")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsGet_whenAnonymous() throws Exception {
            mockMvc.perform(get("/api/users/1"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("returns 404 when user does not exist")
        void returns404_whenMissing() throws Exception {
            when(userService.get(99L)).thenThrow(new ResourceNotFoundException("User not found: 99"));

            mockMvc.perform(get("/api/users/99")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ── GET /api/users (list) ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/users")
    class ListUsers {

        @Test
        @DisplayName("returns 200 with all users when caller has ROLE_ADMIN")
        void listsUsers_asAdmin() throws Exception {
            when(userService.list()).thenReturn(List.of(sampleResponse()));

            mockMvc.perform(get("/api/users")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1L));
        }

        @Test
        @DisplayName("returns 403 when caller has ROLE_USER")
        void rejectsList_asNonAdmin() throws Exception {
            mockMvc.perform(get("/api/users")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }
    }
}