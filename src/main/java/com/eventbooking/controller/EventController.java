package com.eventbooking.controller;

import com.eventbooking.dto.EventDto;
import com.eventbooking.dto.PagedResponse;
import com.eventbooking.entity.Status;
import com.eventbooking.service.EventService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{id}")
    public EventDto.Response get(@PathVariable Long id) {
        return eventService.get(id);
    }

    @GetMapping
    public PagedResponse<EventDto.Response> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) @Min(1) Integer minSeats,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "eventDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        return eventService.search(search, status, minSeats, page, size, sortBy, sortDir);
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
