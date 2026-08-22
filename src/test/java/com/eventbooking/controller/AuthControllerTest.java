package com.eventbooking.controller;

import com.eventbooking.config.SecurityConfig;
import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.Role;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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


@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@EnableWebSecurity
@TestPropertySource(properties = "app.jwt.secret=test-only-secret-key-at-least-32-characters-long")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AuthenticationManager authenticationManager;

    private UserDto.Response sampleUser() {
        return UserDto.Response.builder()
                .id(1L)
                .name("Jane Doe")
                .email("jane@example.com")
                .phone("9876543210")
                .role(Role.USER)
                .build();
    }

    // ── POST /api/auth/login ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("returns 200 with a bearer token on valid credentials")
        void logsIn_withValidCredentials() throws Exception {
            when(authenticationManager.authenticate(any())).thenReturn(
                    new UsernamePasswordAuthenticationToken(
                            "jane@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            when(userService.getByEmail("jane@example.com")).thenReturn(sampleUser());

            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"jane@example.com","password":"password123"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.user.email").value("jane@example.com"));
        }

        @Test
        @DisplayName("returns 401 on bad credentials")
        void rejectsLogin_withBadCredentials() throws Exception {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"jane@example.com","password":"wrongpassword"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }

        @Test
        @DisplayName("returns 400 when email is not a valid format")
        void rejectsLogin_whenEmailInvalid() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"not-an-email","password":"password123"}
                                    """))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authenticationManager);
        }

        @Test
        @DisplayName("returns 400 when password is missing")
        void rejectsLogin_whenPasswordMissing() throws Exception {
            mockMvc.perform(post("/api/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"jane@example.com"}
                                    """))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authenticationManager);
        }
    }

    // ── GET /api/auth/me ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/auth/me")
    class Me {

        @Test
        @DisplayName("returns 200 with the authenticated user's profile")
        void returnsCurrentUser_whenAuthenticated() throws Exception {
            when(userService.getByEmail("jane@example.com")).thenReturn(sampleUser());

            mockMvc.perform(get("/api/auth/me")
                            .with(jwt()
                                    .jwt(builder -> builder.subject("jane@example.com"))
                                    .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("jane@example.com"));
        }

        @Test
        @DisplayName("returns 401 when caller is not authenticated")
        void rejectsMe_whenAnonymous() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(userService);
        }
    }
}