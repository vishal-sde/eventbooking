package com.eventbooking.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum BookingStatus {
    PENDING,
    PAYMENT_PENDING,
    CONFIRMED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED;

    public static final Set<BookingStatus> SEAT_HOLDING = EnumSet.of(PENDING,PAYMENT_PENDING,CONFIRMED);

    private static final Map<BookingStatus,Set<BookingStatus>> ALLOWED = new EnumMap<>(BookingStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(PAYMENT_PENDING,CONFIRMED,CANCELLED));
        ALLOWED.put(PAYMENT_PENDING, EnumSet.of(PENDING, CONFIRMED, CANCELLED)); // back to PENDING = payment failed, retry
        ALLOWED.put(CONFIRMED,       EnumSet.of(CANCELLED, REFUND_PENDING));
        ALLOWED.put(REFUND_PENDING,  EnumSet.of(REFUNDED, REFUND_FAILED));
        ALLOWED.put(REFUND_FAILED,   EnumSet.of(REFUND_PENDING));                // retry
        ALLOWED.put(CANCELLED,       EnumSet.noneOf(BookingStatus.class));
        ALLOWED.put(REFUNDED,        EnumSet.noneOf(BookingStatus.class));

    }
    public boolean canTransitionTo(BookingStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
