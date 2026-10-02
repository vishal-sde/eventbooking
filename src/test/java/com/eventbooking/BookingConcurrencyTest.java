package com.eventbooking;

import com.eventbooking.dto.BookingDto;
import com.eventbooking.dto.EventDto;
import com.eventbooking.entity.BookingStatus;
import com.eventbooking.entity.Role;
import com.eventbooking.entity.Status;
import com.eventbooking.entity.User;
import com.eventbooking.repository.BookingRepository;
import com.eventbooking.repository.EventRepository;
import com.eventbooking.repository.UserRepository;
import com.eventbooking.service.BookingService;
import com.eventbooking.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
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


class BookingConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private BookingService bookingService;
    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final int AVAILABLE_SEATS = 5;
    private static final int CONCURRENT_USERS = 50;

    private Long eventId;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setup() {

        bookingRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();


        EventDto.Response event = eventService.create(
                EventDto.CreateRequest.builder()
                        .name("Concurrency Test Concert")
                        .venue("Test Arena")
                        .eventDate(LocalDateTime.now().plusDays(30))
                        .totalSeats(AVAILABLE_SEATS)
                        .ticketPrice(new BigDecimal("500.0"))
                        .build()
        );
        eventId = event.getId();


        for (int i = 0; i < CONCURRENT_USERS; i++) {
            User user = User.builder()
                    .name("TestUser" + i)
                    .email("testuser" + i + "@test.com")
                    .phone("9" + String.format("%09d", i))
                    .password(passwordEncoder.encode("password123"))
                    .role(Role.USER)
                    .emailVerified(true)
                    .build();
            User saved = userRepository.save(user);
            userIds.add(saved.getId());
        }
    }

    @Test
    @DisplayName("Only 5 bookings succeed when 50 users concurrently book 1 seat on a 5-seat event")
    void onlyAvailableSeatsGetBooked() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);


        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        java.util.Map<String,AtomicInteger> failureTypes = new ConcurrentHashMap<>();

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
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
                    failureTypes.computeIfAbsent(e.getClass().getSimpleName(),k -> new AtomicInteger()).incrementAndGet();
                    failureCount.incrementAndGet();
                    System.out.println("FAILURE [user " + userId + "]: "
                            + e.getClass().getSimpleName()+ " -- " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }


        startLatch.countDown();


        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();


        System.out.println("===========================================");
        System.out.println("Successful bookings: " + successCount.get());
        System.out.println("Failed attempts:     " + failureCount.get());
        System.out.println("===========================================");
        System.out.println("Failure types: " + failureTypes);
        assertThat(failureTypes.keySet())
                .as("Only 'no seats' or 'lock busy' are acceptable failures")
                        .isSubsetOf("SeatsUnavailableException","LockAcquisitionException");

        assertThat(successCount.get())
                .as("Exactly %d bookings should succeed", AVAILABLE_SEATS)
                .isEqualTo(AVAILABLE_SEATS);

        assertThat(failureCount.get())
                .as("Remaining %d attempts should fail", CONCURRENT_USERS - AVAILABLE_SEATS)
                .isEqualTo(CONCURRENT_USERS - AVAILABLE_SEATS);


        var event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getAvailableSeats())
                .as("Available seats in DB must be 0")
                .isEqualTo(0);

        assertThat(event.getStatus())
                .as("Event status must be SOLD_OUT")
                .isEqualTo(Status.SOLD_OUT);


        long confirmedBookings = bookingRepository.findAll()
                .stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED)
                .count();
        assertThat(confirmedBookings)
                .as("Exactly %d confirmed bookings in DB", AVAILABLE_SEATS)
                .isEqualTo(AVAILABLE_SEATS);
    }
}