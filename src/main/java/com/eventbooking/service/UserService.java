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
        return UserDto.Response.from(userRepository.save(user));
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

    User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }
}
