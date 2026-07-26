package com.eventbooking.dto;

import com.eventbooking.entity.WishlistItem;
import lombok.*;

import java.time.LocalDateTime;

public class WishlistDto {

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Response {
        private Long id;
        private EventDto.Response event;
        private LocalDateTime savedAt;

        public static Response from(WishlistItem item) {
            return Response.builder()
                    .id(item.getId())
                    .event(EventDto.Response.from(item.getEvent()))
                    .savedAt(item.getCreatedAt())
                    .build();
        }
    }
}