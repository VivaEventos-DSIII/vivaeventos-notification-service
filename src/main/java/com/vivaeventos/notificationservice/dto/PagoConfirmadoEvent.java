package com.vivaeventos.notificationservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PagoConfirmadoEvent(
        UUID orderId,
        UUID userId,
        String userEmail,
        String userName,
        BigDecimal amount,
        UUID eventId,
        String ticketType,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal discountPct,
        java.time.LocalDateTime confirmedAt
) {}
