package com.eventbooking.service;

import com.eventbooking.dto.ReviewDto;
import com.eventbooking.entity.*;
import com.eventbooking.exception.DuplicateResourceException;
import com.eventbooking.exception.ResourceNotFoundException;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.ReviewRepository;
import com.eventbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public ReviewDto.Response create(String email, Long eventId, ReviewDto.CreateRequest request) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));

        if (event.getStatus() != Status.COMPLETED) {
            throw new IllegalStateException("You can only review events after they have taken place");
        }
        boolean attended = bookingRepository
                .findByUserIdAndEventIdAndStatus(user.getId(), eventId, BookingStatus.CONFIRMED)
                .isPresent();
        if (!attended) {
            throw new IllegalStateException("Only attendees with a confirmed booking can review this event");
        }
        if (reviewRepository.existsByUserIdAndEventId(user.getId(), eventId)) {
            throw new DuplicateResourceException("You have already reviewed this event");
        }

        Review review = Review.builder()
                .user(user)
                .event(event)
                .rating(request.getRating())
                .comment(request.getComment())
                .build();
        return ReviewDto.Response.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public ReviewDto.Summary list(Long eventId) {
        var reviews = reviewRepository.findByEventIdWithUser(eventId).stream()
                .map(ReviewDto.Response::from)
                .toList();
        double average = reviewRepository.averageRating(eventId);
        return ReviewDto.Summary.builder()
                .reviews(reviews)
                .averageRating(Math.round(average * 10) / 10.0)
                .totalReviews(reviews.size())
                .build();
    }
}