package com.vivaeventos.notificationservice.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TicketGeneradoEvent(
        UUID orderId,
        UUID eventId,
        UUID userId,
        String userEmail,
        String userName,
        String eventName,
        LocalDateTime eventDate,
        String venue,
        List<String> ticketCodes
) {}
