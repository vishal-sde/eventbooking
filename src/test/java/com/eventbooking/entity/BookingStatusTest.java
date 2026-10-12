package com.eventbooking.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;


import static com.eventbooking.entity.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class BookingStatusTest {

    @Test
    @DisplayName("happy path: PENDING -> PAYMENT_PENDING -> CONFIRMED")
    void happyPath() {
        assertThat(PENDING.canTransitionTo(PAYMENT_PENDING)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(CONFIRMED)).isTrue();
    }

    @Test
    @DisplayName("failed payment can go back to PENDING for a retry")
    void failedPaymentCanRetry() {
        assertThat(PAYMENT_PENDING.canTransitionTo(PENDING)).isTrue();
    }

    @Test
    @DisplayName("refund path: CONFIRMED -> REFUND_PENDING -> REFUNDED, failures can retry")
    void refundPath() {
        assertThat(CONFIRMED.canTransitionTo(REFUND_PENDING)).isTrue();
        assertThat(REFUND_PENDING.canTransitionTo(REFUNDED)).isTrue();
        assertThat(REFUND_PENDING.canTransitionTo(REFUND_FAILED)).isTrue();
        assertThat(REFUND_FAILED.canTransitionTo(REFUND_PENDING)).isTrue();
    }

    @Test
    @DisplayName("cannot skip payment or refund steps")
    void cannotSkipSteps() {
        assertThat(CONFIRMED.canTransitionTo(PENDING)).isFalse();
        assertThat(CONFIRMED.canTransitionTo(REFUNDED)).isFalse();
        assertThat(PENDING.canTransitionTo(REFUND_PENDING)).isFalse();
        assertThat(CANCELLED.canTransitionTo(CONFIRMED)).isFalse();
    }

    @Test
    @DisplayName("CANCELLED and REFUNDED are terminal")
    void terminalStates() {
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(CONFIRMED.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    @DisplayName("no status can transition to itself")
    void noSelfTransitions(BookingStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    @DisplayName("only PENDING, PAYMENT_PENDING and CONFIRMED hold seats")
    void seatHolding() {
        assertThat(SEAT_HOLDING).containsExactlyInAnyOrder(PENDING, PAYMENT_PENDING, CONFIRMED);
    }
}