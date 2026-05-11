package com.vivaeventos.notificationservice.consumer;

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

    @KafkaListener(topics = "${kafka.topics.order-confirmed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPagoConfirmado(PagoConfirmadoEvent event) {
        log.info("Evento recibido [order.confirmed] orderId={}", event.orderId());
        notificationService.sendConfirmacionCompra(event);
    }

    @KafkaListener(topics = "${kafka.topics.ticket-generated}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTicketGenerado(TicketGeneradoEvent event) {
        log.info("Evento recibido [ticket.generated] ticketId={}", event.ticketId());
        notificationService.sendTicketGenerado(event);
    }

    @KafkaListener(topics = "${kafka.topics.event-cancelled}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEventoCancelado(EventoCanceladoEvent event) {
        log.info("Evento recibido [event.cancelled] eventId={}", event.eventId());
        notificationService.sendEventoCancelado(event);
    }
}
