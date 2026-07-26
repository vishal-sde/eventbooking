package com.eventbooking.repository;

import com.eventbooking.entity.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<WishlistItem, Long> {

    boolean existsByUserIdAndEventId(Long userId, Long eventId);

    Optional<WishlistItem> findByUserIdAndEventId(Long userId, Long eventId);

    @Query("SELECT w FROM WishlistItem w JOIN FETCH w.event WHERE w.user.id = :userId ORDER BY w.createdAt DESC")
    List<WishlistItem> findByUserIdWithEvent(@Param("userId") Long userId);

    void deleteByUserIdAndEventId(Long userId, Long eventId);
}