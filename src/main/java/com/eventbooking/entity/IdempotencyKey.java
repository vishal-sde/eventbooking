package com.eventbooking.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false,length = 100)
    private String endpoint;

    @Column(name = "idem_key",nullable = false,length = 100)
    private String idemKey;

    @Column(nullable = false,length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,length = 20)
    private IdempotencyState state;


    private Integer responseCode;

    @Column(length = 4000)
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;
}
