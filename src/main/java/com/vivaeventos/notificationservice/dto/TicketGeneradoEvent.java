package com.vivaeventos.notificationservice.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record TicketGeneradoEvent(
        UUID ticketId,
        UUID orderId,
        UUID userId,
        String userEmail,
        String userName,
        String eventName,
        LocalDateTime eventDate,
        String venue
) {}
