package com.eventbooking.service;

import com.eventbooking.dto.WishlistDto;
import com.eventbooking.entity.Event;
import com.eventbooking.entity.User;
import com.eventbooking.entity.WishlistItem;
import com.eventbooking.exception.DuplicateResourceException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;

    @Transactional
    public WishlistDto.Response add(String email, Long eventId) {
        User user = findUser(email);
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
        if (wishlistRepository.existsByUserIdAndEventId(user.getId(), eventId)) {
            throw new DuplicateResourceException("Event already saved to your wishlist");
        }
        WishlistItem item = WishlistItem.builder().user(user).event(event).build();
        return WishlistDto.Response.from(wishlistRepository.save(item));
    }

    @Transactional
    public void remove(String email, Long eventId) {
        User user = findUser(email);
        wishlistRepository.deleteByUserIdAndEventId(user.getId(), eventId);
    }

    @Transactional(readOnly = true)
    public List<WishlistDto.Response> list(String email) {
        User user = findUser(email);
        return wishlistRepository.findByUserIdWithEvent(user.getId()).stream()
                .map(WishlistDto.Response::from)
                .toList();
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}