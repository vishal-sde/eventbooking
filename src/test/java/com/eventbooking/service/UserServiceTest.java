package com.eventbooking.service;

import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.Role;
import com.eventbooking.entity.User;
import com.eventbooking.exception.DuplicateResourceException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private OtpService otpService;
    @Mock private PasswordResetService passwordResetService;

    @InjectMocks
    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .name("John Doe")
                .email("john@example.com")
                .phone("9876543210")
                .password("$2a$10$hashedpassword")
                .role(Role.USER)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("does NOT save a user row — only stages a pending registration and sends an OTP")
        void doesNotPersistUser() {
            UserDto.CreateRequest request = UserDto.CreateRequest.builder()
                    .name("John Doe")
                    .email("john@example.com")
                    .phone("9876543210")
                    .password("password123")
                    .build();

            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hashed");
            when(otpService.startRegistration("John Doe", "john@example.com", "9876543210", "$2a$10$hashed"))
                    .thenReturn("123456");

            userService.create(request);

            verify(userRepository, never()).save(any());
            verify(emailService).sendOtpEmail("john@example.com", "123456");
        }

        @Test
        @DisplayName("throws DuplicateResourceException when a verified account with this email already exists")
        void throwsOnDuplicateEmail() {
            UserDto.CreateRequest request = UserDto.CreateRequest.builder()
                    .name("Jane Doe")
                    .email("john@example.com")
                    .phone("9876543210")
                    .password("password123")
                    .build();

            when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.create(request))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("already exists");

            verify(otpService, never()).startRegistration(any(), any(), any(), any());
        }

        @Test
        @DisplayName("normalises email to lowercase before staging registration")
        void normalisesEmailToLowercase() {
            UserDto.CreateRequest request = UserDto.CreateRequest.builder()
                    .name("John Doe")
                    .email("JOHN@EXAMPLE.COM")
                    .phone("9876543210")
                    .password("password123")
                    .build();

            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");

            userService.create(request);

            verify(otpService).startRegistration(eq("John Doe"), eq("john@example.com"), any(), any());
        }

        @Test
        @DisplayName("trims whitespace from name/email/phone before staging registration")
        void trimsWhitespace() {
            UserDto.CreateRequest request = UserDto.CreateRequest.builder()
                    .name("  John Doe  ")
                    .email("  john@example.com  ")
                    .phone("9876543210")
                    .password("password123")
                    .build();

            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");

            userService.create(request);

            verify(otpService).startRegistration(eq("John Doe"), eq("john@example.com"), any(), any());
        }

        @Test
        @DisplayName("encodes password before staging registration — never stores plaintext")
        void encodesPassword() {
            UserDto.CreateRequest request = UserDto.CreateRequest.builder()
                    .name("John Doe")
                    .email("john@example.com")
                    .phone("9876543210")
                    .password("plaintext123")
                    .build();

            when(userRepository.existsByEmail(any())).thenReturn(false);
            when(passwordEncoder.encode("plaintext123")).thenReturn("$2a$10$encoded");

            userService.create(request);

            verify(otpService).startRegistration(any(), any(), any(), eq("$2a$10$encoded"));
        }
    }

    // ── verifyOtp() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyOtp()")
    class VerifyOtp {

        @Test
        @DisplayName("creates the user row only after a correct OTP, with emailVerified true")
        void createsUserOnCorrectOtp() {
            OtpService.PendingRegistration pending = new OtpService.PendingRegistration(
                    "John Doe", "john@example.com", "9876543210", "$2a$10$hashed", "123456");

            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(otpService.verify("john@example.com", "123456")).thenReturn(java.util.Optional.of(pending));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            UserDto.Response response = userService.verifyOtp("john@example.com", "123456");

            assertThat(response).isNotNull();
            verify(userRepository).save(argThat(u ->
                    u.getEmail().equals("john@example.com") && u.isEmailVerified() && u.getRole() == Role.USER
            ));
            verify(emailService).sendRegistrationConfirmation(any());
        }

        @Test
        @DisplayName("throws and does not save when the OTP is wrong or expired")
        void throwsOnBadOtp() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(otpService.verify("john@example.com", "000000")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> userService.verifyOtp("john@example.com", "000000"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid or expired");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws when the account is already verified, without touching Redis")
        void throwsWhenAlreadyVerified() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.verifyOtp("john@example.com", "123456"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already verified");

            verify(otpService, never()).verify(any(), any());
        }
    }

    // ── resendOtp() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resendOtp()")
    class ResendOtp {

        @Test
        @DisplayName("issues a new OTP when a pending registration exists and cooldown has passed")
        void resendsSuccessfully() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(otpService.hasPendingRegistration("john@example.com")).thenReturn(true);
            when(otpService.canResend("john@example.com")).thenReturn(true);
            when(otpService.resend("john@example.com")).thenReturn(java.util.Optional.of("654321"));

            userService.resendOtp("john@example.com");

            verify(emailService).sendOtpEmail("john@example.com", "654321");
        }

        @Test
        @DisplayName("throws when the cooldown hasn't elapsed yet")
        void throwsDuringCooldown() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(otpService.hasPendingRegistration("john@example.com")).thenReturn(true);
            when(otpService.canResend("john@example.com")).thenReturn(false);

            assertThatThrownBy(() -> userService.resendOtp("john@example.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wait");

            verify(emailService, never()).sendOtpEmail(any(), any());
        }

        @Test
        @DisplayName("throws when there is no pending registration to resend for")
        void throwsWhenNoPendingRegistration() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(false);
            when(otpService.hasPendingRegistration("john@example.com")).thenReturn(false);

            assertThatThrownBy(() -> userService.resendOtp("john@example.com"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("throws when the account is already verified")
        void throwsWhenAlreadyVerified() {
            when(userRepository.existsByEmail("john@example.com")).thenReturn(true);

            assertThatThrownBy(() -> userService.resendOtp("john@example.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already verified");
        }
    }

    // ── forgotPassword() ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("forgotPassword()")
    class ForgotPassword {

        @Test
        @DisplayName("sends a reset code when the account exists and cooldown has passed")
        void sendsResetCodeForExistingAccount() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(java.util.Optional.of(testUser));
            when(passwordResetService.canResend("john@example.com")).thenReturn(true);
            when(passwordResetService.generate("john@example.com")).thenReturn("123456");

            userService.forgotPassword("john@example.com");

            verify(emailService).sendPasswordResetEmail("john@example.com", "123456");
        }

        @Test
        @DisplayName("does nothing — and does not throw — for an email that isn't registered")
        void silentlyNoOpsForUnknownEmail() {
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(java.util.Optional.empty());

            userService.forgotPassword("ghost@example.com");

            verify(emailService, never()).sendPasswordResetEmail(any(), any());
            verify(passwordResetService, never()).generate(any());
        }

        @Test
        @DisplayName("does nothing during the cooldown, without throwing or leaking that a cooldown is active")
        void silentlyNoOpsDuringCooldown() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(java.util.Optional.of(testUser));
            when(passwordResetService.canResend("john@example.com")).thenReturn(false);

            userService.forgotPassword("john@example.com");

            verify(emailService, never()).sendPasswordResetEmail(any(), any());
        }
    }

    // ── resetPassword() ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("resetPassword()")
    class ResetPassword {

        @Test
        @DisplayName("updates the password and sends a notice on a correct code")
        void resetsPasswordOnCorrectCode() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(java.util.Optional.of(testUser));
            when(passwordResetService.verify("john@example.com", "123456")).thenReturn(true);
            when(passwordEncoder.encode("newPassword123")).thenReturn("$2a$10$newHash");

            userService.resetPassword("john@example.com", "123456", "newPassword123");

            verify(userRepository).save(argThat(u -> u.getPassword().equals("$2a$10$newHash")));
            verify(emailService).sendPasswordChangedNotice("john@example.com");
        }

        @Test
        @DisplayName("throws and does not save when the code is wrong or expired")
        void throwsOnBadCode() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(java.util.Optional.of(testUser));
            when(passwordResetService.verify("john@example.com", "000000")).thenReturn(false);

            assertThatThrownBy(() -> userService.resetPassword("john@example.com", "000000", "newPassword123"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid or expired");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws the same message for an unknown email as for a bad code — no enumeration")
        void sameErrorForUnknownEmail() {
            when(userRepository.findByEmail("ghost@example.com")).thenReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> userService.resetPassword("ghost@example.com", "123456", "newPassword123"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid or expired");
        }
    }

    // ── get() ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("returns user DTO when user found")
        void returnsUserWhenFound() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

            UserDto.Response response = userService.get(1L);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getName()).isEqualTo("John Doe");
            assertThat(response.getEmail()).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when user not found")
        void throwsWhenNotFound() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.get(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
        }
    }

    // ── list() ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("list()")
    class ListUsers {

        @Test
        @DisplayName("returns all users as response DTOs")
        void returnsAllUsers() {
            User secondUser = User.builder()
                    .id(2L).name("Jane Doe")
                    .email("jane@example.com")
                    .phone("9876543211")
                    .role(Role.USER)
                    .build();

            when(userRepository.findAll()).thenReturn(List.of(testUser, secondUser));

            List<UserDto.Response> responses = userService.list();

            assertThat(responses).hasSize(2);
            assertThat(responses).extracting(UserDto.Response::getEmail)
                    .containsExactlyInAnyOrder("john@example.com", "jane@example.com");
        }

        @Test
        @DisplayName("returns empty list when no users exist")
        void returnsEmptyList() {
            when(userRepository.findAll()).thenReturn(List.of());

            List<UserDto.Response> responses = userService.list();

            assertThat(responses).isEmpty();
        }
    }

    // ── getByEmail() ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getByEmail()")
    class GetByEmail {

        @Test
        @DisplayName("returns user DTO for valid email")
        void returnsUserForEmail() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));

            UserDto.Response response = userService.getByEmail("john@example.com");

            assertThat(response.getEmail()).isEqualTo("john@example.com");
        }

        @Test
        @DisplayName("normalises email to lowercase before lookup")
        void normalisesEmailForLookup() {
            when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(testUser));

            userService.getByEmail("JOHN@EXAMPLE.COM");

            verify(userRepository).findByEmail("john@example.com");
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when email not found")
        void throwsWhenEmailNotFound() {
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.getByEmail("unknown@example.com"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}