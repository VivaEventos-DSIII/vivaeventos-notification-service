package com.vivaeventos.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.EventoCreadoEvent;
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
 * Recibe el mensaje como String y lo deserializa manualmente con ObjectMapper.
 * Esto evita el error "RecordDeserializationException" que ocurre cuando
 * el deserializador automático de Kafka no sabe a qué clase convertir el JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationService notificationService;

    // ObjectMapper con soporte para LocalDateTime (JavaTimeModule)
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * US-08: Confirmación de compra.
     * Topic: order.confirmed
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
     * US-08: Boleta generada con detalles del evento.
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
     * US-09: Programar recordatorio 24h antes del evento.
     * Topic: event.created
     */
    @KafkaListener(
            topics = "${kafka.topics.event-created}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onEventoCreado(String payload) {
        try {
            EventoCreadoEvent event = objectMapper.readValue(payload, EventoCreadoEvent.class);
            log.info("Evento recibido [event.created] eventId={} fecha={}",
                    event.eventId(), event.eventDate());
            notificationService.scheduleRecordatorio(event);
        } catch (Exception e) {
            log.error("Error procesando [event.created]: {}", e.getMessage());
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