package com.vivaeventos.notificationservice.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record EventoCanceladoEvent(
        UUID eventId,
        String eventName,
        LocalDateTime eventDate,
        UUID userId,
        String userEmail,
        String userName
) {}
