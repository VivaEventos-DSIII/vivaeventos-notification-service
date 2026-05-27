package com.vivaeventos.notificationservice.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Evento que llega desde Kafka cuando el event-service crea un evento nuevo.
 * Topic: event.created
 *
 * Lo usamos en US-09: al recibir este evento, programamos un job de Quartz
 * para enviar el recordatorio 24 horas antes de la fecha del evento.
 *
 * Es un record de Java (inmutable, sin boilerplate) — equivalente a una clase
 * con constructor, getters y equals/hashCode automáticos.
 */
public record EventoCreadoEvent(
        UUID eventId,
        String eventName,
        LocalDateTime eventDate,
        String venue
) {}