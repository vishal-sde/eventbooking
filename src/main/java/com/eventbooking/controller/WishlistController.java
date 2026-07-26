package com.eventbooking.controller;

import com.eventbooking.dto.WishlistDto;
import com.eventbooking.service.WishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class WishlistController {

    private final WishlistService wishlistService;

    @GetMapping
    public List<WishlistDto.Response> list(Authentication authentication) {
        return wishlistService.list(authentication.getName());
    }

    @PostMapping("/{eventId}")
    @ResponseStatus(HttpStatus.CREATED)
    public WishlistDto.Response add(Authentication authentication, @PathVariable Long eventId) {
        return wishlistService.add(authentication.getName(), eventId);
    }

    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication authentication, @PathVariable Long eventId) {
        wishlistService.remove(authentication.getName(), eventId);
    }
}