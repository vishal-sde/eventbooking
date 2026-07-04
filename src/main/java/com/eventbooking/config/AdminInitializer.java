package com.eventbooking.config;

import com.eventbooking.entity.Role;
import com.eventbooking.entity.User;
import com.eventbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String email;

    @Value("${app.admin.password:}")
    private String password;

    @Value("${app.admin.name:Administrator}")
    private String name;

    @Override
    public void run(String... args) {
        if (email.isBlank() || password.isBlank() || userRepository.existsByEmail(email.trim().toLowerCase())) {
            return;
        }
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalStateException("ADMIN_PASSWORD must contain 8 to 72 characters");
        }
        userRepository.save(User.builder()
                .name(name.trim())
                .email(email.trim().toLowerCase())
                .phone("0000000000")
                .password(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .build());
    }
}

