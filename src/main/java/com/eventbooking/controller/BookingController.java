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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BookingDto.Response> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BookingDto.CreateRequest request) {
        var result = bookingService.createIdempotent(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
    @PreAuthorize("hasRole('ADMIN')")
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
