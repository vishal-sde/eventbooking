package com.eventbooking.controller;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {
    private final BookingService bookingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public BookingDto.Response create(@Valid @RequestBody BookingDto.CreateRequest request) {
        return bookingService.create(request);
    }
    @PostMapping("/{reference}/confirm")
    public ResponseEntity<BookingDto.Response> confirm(@PathVariable String reference) {
        return ResponseEntity.ok(bookingService.confirm(reference));
    }

    @GetMapping("/{reference}")
    public BookingDto.Response get(@PathVariable String reference) {
        return bookingService.get(reference);
    }

    @GetMapping
    public List<BookingDto.Response> list(@RequestParam(required = false) Long userId) {
        return bookingService.list(userId);
    }

    @DeleteMapping("/{reference}")
    public BookingDto.Response cancel(@PathVariable String reference) {
        return bookingService.cancel(reference);
    }
}
