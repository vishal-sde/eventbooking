package com.eventbooking.dto;

import com.eventbooking.entity.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

public class ReviewDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotNull(message = "Rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        private Integer rating;

        @Size(max = 1000, message = "Comment is too long")
        private String comment;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Long id;
        private Long userId;
        private String userName;
        private Long eventId;
        private Integer rating;
        private String comment;
        private LocalDateTime createdAt;

        public static Response from(Review review) {
            return Response.builder()
                    .id(review.getId())
                    .userId(review.getUser().getId())
                    .userName(review.getUser().getName())
                    .eventId(review.getEvent().getId())
                    .rating(review.getRating())
                    .comment(review.getComment())
                    .createdAt(review.getCreatedAt())
                    .build();
        }
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Summary {
        private List<Response> reviews;
        private double averageRating;
        private long totalReviews;
    }
}