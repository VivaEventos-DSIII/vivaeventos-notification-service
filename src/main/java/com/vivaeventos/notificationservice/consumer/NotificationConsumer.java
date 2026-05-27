package com.vivaeventos.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import com.vivaeventos.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de Kafka.
 *
 * FIX calidad: ObjectMapper se inyecta como bean de Spring en lugar de
 * instanciarse manualmente. Esto respeta la configuración global de Jackson
 * (fechas, módulos, etc.) definida en el contexto de Spring.
 *
 * FIX US-09: eliminado el listener de event.created — el recordatorio
 * ahora se programa en sendTicketGenerado, donde sí hay email del comprador.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;

    // FIX: ObjectMapper inyectado por Spring, no instanciado manualmente.
    // Spring Boot autoconfigura este bean con JavaTimeModule y otras configuraciones globales.
    private final ObjectMapper objectMapper;

    /**
     * US-08: Confirmación de pago.
     * Topic: order.confirmed → envía email de confirmación de compra.
     */
    @KafkaListener(
            topics = "${kafka.topics.order-confirmed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPagoConfirmado(String payload) {
        try {
            PagoConfirmadoEvent event = objectMapper.readValue(payload, PagoConfirmadoEvent.class);
            log.info("Evento recibido [order.confirmed] orderId={}", event.orderId());
            notificationService.sendConfirmacionCompra(event);
        } catch (Exception e) {
            log.error("Error procesando [order.confirmed]: {}", e.getMessage());
        }
    }

    /**
     * US-08: Boleta generada.
     * US-09: También programa el recordatorio 24h antes (con email real del comprador).
     * Topic: ticket.generated
     */
    @KafkaListener(
            topics = "${kafka.topics.ticket-generated}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onTicketGenerado(String payload) {
        try {
            TicketGeneradoEvent event = objectMapper.readValue(payload, TicketGeneradoEvent.class);
            log.info("Evento recibido [ticket.generated] ticketId={}", event.ticketId());
            notificationService.sendTicketGenerado(event);
        } catch (Exception e) {
            log.error("Error procesando [ticket.generated]: {}", e.getMessage());
        }
    }

    /**
     * Cancelación de evento.
     * Topic: event.cancelled
     */
    @KafkaListener(
            topics = "${kafka.topics.event-cancelled}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onEventoCancelado(String payload) {
        try {
            EventoCanceladoEvent event = objectMapper.readValue(payload, EventoCanceladoEvent.class);
            log.info("Evento recibido [event.cancelled] eventId={}", event.eventId());
            notificationService.sendEventoCancelado(event);
        } catch (Exception e) {
            log.error("Error procesando [event.cancelled]: {}", e.getMessage());
        }
    }
}