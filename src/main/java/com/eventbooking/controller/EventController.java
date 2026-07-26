package com.eventbooking.controller;

import com.eventbooking.dto.EventDto;
import com.eventbooking.dto.PagedResponse;
import com.eventbooking.entity.Category;
import com.eventbooking.entity.Status;
import com.eventbooking.service.EventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Validated
public class EventController {
    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public EventDto.Response create(@Valid @RequestBody EventDto.CreateRequest request) {
        return eventService.create(request);
    }

    @GetMapping("/categories")
    public Category[] categories() {
        return Category.values();
    }

    @GetMapping("/{id}")
    public EventDto.Response get(@PathVariable Long id) {
        return eventService.get(id);
    }

    @GetMapping
    public PagedResponse<EventDto.Response> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) @Min(1) Integer minSeats,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "eventDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        return eventService.search(search, status, minSeats, category, city, minPrice, maxPrice, dateFrom,
                page, size, sortBy, sortDir);
    }

    @PutMapping("/{id}")
    public EventDto.Response update(@PathVariable Long id, @Valid @RequestBody EventDto.UpdateRequest request) {
        return eventService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public EventDto.Response cancel(@PathVariable Long id) {
        return eventService.cancel(id);
    }
}