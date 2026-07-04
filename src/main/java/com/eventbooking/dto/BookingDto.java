package com.eventbooking.dto;

import com.eventbooking.entity.Booking;
import com.eventbooking.entity.BookingStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

public class BookingDto {
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {

        @NotNull(message = "User id is required")
        private Long userId;

        @NotNull(message = "Event id is required")
        private Long eventId;

        @NotNull(message = "Number of seats is required")
        @Min(value = 1,message = "Must book at least 1 ticket")
        @Max(value = 10,message = "Cannot book more than 10 seats at once")
        private Integer seatsRequired;

    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private Long id;
        private String bookingRef;
        private Long userId;
        private String userName;
        private Long eventId;
        private String eventName;
        private Integer seatsBooked;
        private Double totalAmount;
        private BookingStatus status;
        private LocalDateTime bookedAt;
        private LocalDateTime cancelledAt;
        private LocalDateTime expiresAt;

        public static Response from(Booking booking){
            Long userId = null;
            String userName = null;
            Long eventId = null;
            String eventName = null;

            try {
                userId = booking.getUser().getId();
                userName = booking.getUser().getName();
            } catch (Exception ignored) {}

            try {
                eventId = booking.getEvent().getId();
                eventName = booking.getEvent().getName();
            } catch (Exception ignored) {}

            return Response.builder()
                    .id(booking.getId())
                    .bookingRef(booking.getBookingRef())
                    .userId(booking.getUser().getId())
                    .userName(booking.getUser().getName())
                    .eventId(booking.getEvent().getId())
                    .eventName(booking.getEvent().getName())
                    .seatsBooked(booking.getSeatsBooked())
                    .totalAmount(booking.getTotalAmount())
                    .status(booking.getStatus())
                    .bookedAt(booking.getBookedAt())
                    .cancelledAt(booking.getCancelledAt())
                    .expiresAt(booking.getExpiresAt())
                    .build();
        }
    }
}
