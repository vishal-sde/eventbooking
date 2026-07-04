package com.eventbooking.controller;

import com.eventbooking.dto.UserDto;
import com.eventbooking.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto.Response create(@Valid @RequestBody UserDto.CreateRequest request) {
        return userService.create(request);
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
