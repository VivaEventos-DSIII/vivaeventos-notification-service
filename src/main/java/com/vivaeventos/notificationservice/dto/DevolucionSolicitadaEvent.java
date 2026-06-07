package com.vivaeventos.notificationservice.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento recibido desde Kafka cuando el order-service registra
 * una solicitud de devolución.
 * Topic: order.refund-requested
 *
 * Contiene el email y nombre del cliente para enviarle la confirmación
 * y el monto para mostrarlo en el correo.
 */
public record DevolucionSolicitadaEvent(
        UUID orderId,
        UUID eventId,
        UUID customerId,
        String userEmail,
        String userName,
        BigDecimal totalAmount,
        String reason,
        LocalDateTime requestedAt
) {}
