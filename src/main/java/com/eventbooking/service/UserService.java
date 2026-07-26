package com.eventbooking.service;

import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.User;
import com.eventbooking.entity.Role;
import com.eventbooking.exception.DuplicateResourceException;
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

    @Transactional
    public UserDto.Response create(UserDto.CreateRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("A user with this email already exists");
        }
        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .phone(request.getPhone().trim())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();
        User saved = userRepository.save(user);
        emailService.sendRegistrationConfirmation(saved);
        return UserDto.Response.from(saved);
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