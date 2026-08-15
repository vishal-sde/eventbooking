package com.eventbooking.controller;

import com.eventbooking.dto.UserDto;
import com.eventbooking.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void create(@Valid @RequestBody UserDto.CreateRequest request) {
        userService.create(request);
    }

    @GetMapping("/me")
    public UserDto.Response me(Authentication authentication) {
        return userService.getByEmail(authentication.getName());
    }

    @PutMapping("/me")
    public UserDto.Response updateProfile(Authentication authentication, @Valid @RequestBody UserDto.UpdateProfileRequest request) {
        return userService.updateProfile(authentication.getName(), request);
    }

    @PutMapping("/me/password")
    public void changePassword(Authentication authentication, @Valid @RequestBody UserDto.PasswordChangeRequest request) {
        userService.changePassword(authentication.getName(), request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto.Response get(@PathVariable Long id) {
        return userService.get(id);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserDto.Response> list() {
        return userService.list();
    }
}