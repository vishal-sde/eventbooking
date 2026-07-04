package com.eventbooking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings",indexes = {@Index(name = "id_booking_ref",columnList = "bookingRef"),
                                    @Index(name = "id_booking_user_event",columnList = "user_id,event_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true)
    private String bookingRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id",nullable = false)
    private Event event;

    @Column(nullable = false)
    private Integer seatsBooked;

    @Column(nullable = false)
    private Double totalAmount;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    @Column(nullable = false,updatable = false)
    private LocalDateTime bookedAt;

    private LocalDateTime cancelledAt;

    private LocalDateTime expiresAt;

    @PrePersist
    private void onCreate(){
        bookedAt  = LocalDateTime.now();
        if(bookingRef == null){
            bookingRef = "BK-" + UUID.randomUUID().toString().substring(0,8).toUpperCase();
        }
    }
}
