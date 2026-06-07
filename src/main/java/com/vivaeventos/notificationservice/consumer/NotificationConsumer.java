package com.vivaeventos.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivaeventos.notificationservice.dto.DevolucionSolicitadaEvent;
import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import com.vivaeventos.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** US-08: Confirmación de pago. Topic: order.confirmed */
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

    /** US-08 + US-09: Boleta generada + programar recordatorio. Topic: ticket.generated */
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

    /** Cancelación de evento. Topic: event.cancelled */
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

    /**
     * US-13: Confirmación de devolución.
     * Topic: order.refund-requested
     * Criterio: "Dado que la devolución se procesa entonces el cliente
     *            debe recibir confirmación."
     */
    @KafkaListener(
            topics = "${kafka.topics.order-refund-requested}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onDevolucionSolicitada(String payload) {
        try {
            DevolucionSolicitadaEvent event = objectMapper.readValue(
                    payload, DevolucionSolicitadaEvent.class);
            log.info("Evento recibido [order.refund-requested] orderId={}", event.orderId());
            notificationService.sendConfirmacionDevolucion(event);
        } catch (Exception e) {
            log.error("Error procesando [order.refund-requested]: {}", e.getMessage());
        }
    }
}