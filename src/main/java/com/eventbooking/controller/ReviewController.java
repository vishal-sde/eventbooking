package com.eventbooking.controller;

import com.eventbooking.dto.ReviewDto;
import com.eventbooking.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events/{eventId}/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ReviewDto.Summary list(@PathVariable Long eventId) {
        return reviewService.list(eventId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ReviewDto.Response create(Authentication authentication, @PathVariable Long eventId,
                                     @Valid @RequestBody ReviewDto.CreateRequest request) {
        return reviewService.create(authentication.getName(), eventId, request);
    }
}