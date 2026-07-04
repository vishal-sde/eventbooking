package com.eventbooking.dto;

import com.eventbooking.entity.Event;
import com.eventbooking.entity.Status;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;

public class EventDto {
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotBlank(message = "Event name is required")
        private String name;

        @NotBlank(message = "Venue is required")
        private String venue;

        @NotNull(message = "Event date is required")
        @Future(message = "Event date must be in future")
        private LocalDateTime eventDate;

        @NotNull(message = "Number of seats required")
        @Min(value = 1,message = "At least 1 seat is required")
        @Max(value = 10000,message = "Cannot exceed 10000 seats")
        private Integer totalSeats;

        @NotNull(message = "Ticket price is required")
        @DecimalMin(value = "0.0",message = "Price cannot be negative")
        private Double ticketPrice;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateRequest {
        @NotBlank(message = "Event name is required")
        private String name;

        @NotBlank(message = "Venue is required")
        private String venue;

        @NotNull(message = "Event date is required")
        @Future(message = "Event date must be in the future")
        private LocalDateTime eventDate;

        @NotNull(message = "Ticket price is required")
        @DecimalMin(value = "0.0", message = "Price cannot be negative")
        private Double ticketPrice;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Response {

        private Long id;
        private String name;
        private String venue;
        private LocalDateTime eventDate;
        private Integer totalSeats;
        private Integer availableSeats;
        private Double ticketPrice;
        private Status status;
        private Long version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;


        public static Response from(Event event){
            return Response.builder()
                    .id(event.getId())
                    .name(event.getName())
                    .venue(event.getVenue())
                    .eventDate(event.getEventDate())
                    .totalSeats(event.getTotalSeats())
                    .availableSeats(event.getAvailableSeats())
                    .ticketPrice(event.getTicketPrice())
                    .status(event.getStatus())
                    .version(event.getVersion())
                    .createdAt(event.getCreatedAt())
                    .updatedAt(event.getUpdatedAt())
                    .build();

        }
    }
}
