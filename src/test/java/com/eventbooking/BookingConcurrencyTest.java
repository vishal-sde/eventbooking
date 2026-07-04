package com.eventbooking;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.dto.EventDto;
import com.eventbooking.dto.UserDto;
import com.eventbooking.entity.BookingStatus;
import com.eventbooking.entity.Status;
import com.eventbooking.entity.User;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.BookingService;
import com.eventbooking.service.EventService;
import com.eventbooking.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BookingConcurrencyTest {

    @Autowired private BookingService bookingService;
    @Autowired private EventService eventService;
    @Autowired private UserService userService;
    @Autowired private EventRepository eventRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;

    private static final int AVAILABLE_SEATS = 5;
    private static final int CONCURRENT_USERS = 50;

    private Long eventId;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        // Clean slate before each test
        bookingRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();

        // Create event with only 5 seats
        EventDto.Response event = eventService.create(
                EventDto.CreateRequest.builder()
                        .name("Concurrency Test Concert")
                        .venue("Test Arena")
                        .eventDate(LocalDateTime.now().plusDays(30))
                        .totalSeats(AVAILABLE_SEATS)
                        .ticketPrice(500.0)
                        .build()
        );
        eventId = event.getId();

        // Create 50 unique users
        for (int i = 0; i < CONCURRENT_USERS; i++) {
            UserDto.Response user = userService.create(
                    UserDto.CreateRequest.builder()
                            .name("TestUser" + i)
                            .email("testuser" + i + "@test.com")
                            .phone("9" + String.format("%09d", i))
                            .password("password123")
                            .build()
            );
            userIds.add(user.getId());
        }
    }

    @Test
    @DisplayName("Only 5 bookings succeed when 50 users concurrently book 1 seat on a 5-seat event")
    void onlyAvailableSeatsGetBooked() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);

        // This latch holds all threads at the starting line
        // until we release them all at once
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    // All threads wait here until startLatch.countDown() is called
                    // This ensures maximum concurrency — all 50 hit bookSeats() simultaneously
                    startLatch.await();

                    User user = userRepository.findById(userId).orElseThrow();
                    var auth = new UsernamePasswordAuthenticationToken(
                            user.getEmail(),
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
                    var context = new SecurityContextImpl(auth);
                    SecurityContextHolder.setContext(context);

                    bookingService.create(
                            BookingDto.CreateRequest.builder()
                                    .userId(userId)
                                    .eventId(eventId)
                                    .seatsRequired(1)
                                    .build()
                    );
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    System.out.println("FAILURE [user " + userId + "]: "
                            + e.getClass().getSimpleName()+ " -- " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // RELEASE — all 50 threads start simultaneously
        startLatch.countDown();

        // Wait for all threads to finish (max 30 seconds)
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Print results so you can see them in the test output
        System.out.println("===========================================");
        System.out.println("Successful bookings: " + successCount.get());
        System.out.println("Failed attempts:     " + failureCount.get());
        System.out.println("===========================================");

        // THE CRITICAL ASSERTIONS
        // Exactly 5 should succeed — not 4, not 6, exactly 5
        assertThat(successCount.get())
                .as("Exactly %d bookings should succeed", AVAILABLE_SEATS)
                .isEqualTo(AVAILABLE_SEATS);

        assertThat(failureCount.get())
                .as("Remaining %d attempts should fail", CONCURRENT_USERS - AVAILABLE_SEATS)
                .isEqualTo(CONCURRENT_USERS - AVAILABLE_SEATS);

        // DB must show 0 available seats
        var event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getAvailableSeats())
                .as("Available seats in DB must be 0")
                .isEqualTo(0);

        assertThat(event.getStatus())
                .as("Event status must be SOLD_OUT")
                .isEqualTo(Status.SOLD_OUT);

        // Exactly 5 confirmed bookings in DB — no phantom bookings
        long confirmedBookings = bookingRepository.findAll()
                .stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .count();
        assertThat(confirmedBookings)
                .as("Exactly %d confirmed bookings in DB", AVAILABLE_SEATS)
                .isEqualTo(AVAILABLE_SEATS);
    }
}