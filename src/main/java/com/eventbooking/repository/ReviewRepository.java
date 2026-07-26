package com.eventbooking.repository;

import com.eventbooking.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    @Query("SELECT r FROM Review r JOIN FETCH r.user WHERE r.event.id = :eventId ORDER BY r.createdAt DESC")
    List<Review> findByEventIdWithUser(@Param("eventId") Long eventId);

    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r WHERE r.event.id = :eventId")
    Double averageRating(@Param("eventId") Long eventId);

    long countByEventId(Long eventId);
}