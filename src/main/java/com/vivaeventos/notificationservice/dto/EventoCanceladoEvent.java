package com.vivaeventos.notificationservice.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record EventoCanceladoEvent(
        UUID eventId,
        String eventName,
        LocalDateTime eventDate,
        String venue,
        UUID organizerId,
        String reason
) {}
