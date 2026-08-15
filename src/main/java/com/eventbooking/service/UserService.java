package com.eventbooking.service;

import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.User;
import com.eventbooking.entity.Role;
import com.eventbooking.exception.DuplicateResourceException;
import com.eventbooking.exception.EmailNotVerifiedException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final OtpService otpService;

    @Transactional(readOnly = true)
    public void create(UserDto.CreateRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("A user with this email already exists");
        }
        String passwordHash = passwordEncoder.encode(request.getPassword());
        String otp = otpService.startRegistration(request.getName().trim(), email, request.getPhone().trim(), passwordHash);
        emailService.sendOtpEmail(email, otp);
    }

    @Transactional
    public UserDto.Response verifyOtp(String email, String otp) {
        String normalized = email.trim().toLowerCase();
        if (userRepository.existsByEmail(normalized)) {
            throw new IllegalStateException("This account is already verified");
        }
        OtpService.PendingRegistration pending = otpService.verify(normalized, otp)
                .orElseThrow(() -> new IllegalStateException("Invalid or expired code. Request a new one and try again."));
        User user = User.builder()
                .name(pending.name())
                .email(pending.email())
                .phone(pending.phone())
                .password(pending.passwordHash())
                .role(Role.USER)
                .emailVerified(true)
                .build();
        User saved = userRepository.save(user);
        emailService.sendRegistrationConfirmation(saved);
        return UserDto.Response.from(saved);
    }

    @Transactional(readOnly = true)
    public void resendOtp(String email) {
        String normalized = email.trim().toLowerCase();
        if (userRepository.existsByEmail(normalized)) {
            throw new IllegalStateException("This account is already verified");
        }
        if (!otpService.hasPendingRegistration(normalized)) {
            throw new ResourceNotFoundException("No pending registration found for this email. Please register again.");
        }
        if (!otpService.canResend(normalized)) {
            throw new IllegalStateException("Please wait a moment before requesting another code");
        }
        String otp = otpService.resend(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("No pending registration found for this email. Please register again."));
        emailService.sendOtpEmail(normalized, otp);
    }

    /**
     * Called from AuthController after password authentication succeeds,
     * before a token is issued. Now effectively a defensive backstop rather
     * than the primary gate: since verifyOtp() is the only path that writes
     * a self-registered user into the database, and it only ever writes
     * emailVerified=true, an unverified row should never exist here at all.
     * Kept as a second check in case a future code path creates a User
     * directly without going through OTP verification.
     */
    @Transactional(readOnly = true)
    public void requireVerified(String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException(
                    "Please verify your email before logging in. Check your inbox for the code, or request a new one.");
        }
    }

    @Transactional
    public UserDto.Response findOrCreateOAuthUser(String email, String name) {
        String normalized = email.trim().toLowerCase();
        User existing = userRepository.findByEmail(normalized).orElse(null);
        if (existing != null) {
            return UserDto.Response.from(existing);
        }
        User user = User.builder()
                .name(name.trim())
                .email(normalized)
                .phone("") // not collected by Google sign-in; user can add it later from their profile
                .password(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                .role(Role.USER)
                .emailVerified(true) // Google already verified this email address
                .build();
        User saved = userRepository.save(user);
        emailService.sendRegistrationConfirmation(saved);
        return UserDto.Response.from(saved);
    }

    @Transactional(readOnly = true)
    public UserDto.Response get(Long id) {
        return UserDto.Response.from(findUser(id));
    }

    @Transactional(readOnly = true)
    public List<UserDto.Response> list() {
        return userRepository.findAll().stream().map(UserDto.Response::from).toList();
    }

    @Transactional(readOnly = true)
    public UserDto.Response getByEmail(String email) {
        return UserDto.Response.from(userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found")));
    }

    @Transactional
    public UserDto.Response updateProfile(String email, UserDto.UpdateProfileRequest request) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setName(request.getName().trim());
        user.setPhone(request.getPhone().trim());
        return UserDto.Response.from(userRepository.save(user));
    }

    @Transactional
    public void changePassword(String email, UserDto.PasswordChangeRequest request) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IllegalStateException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }
}