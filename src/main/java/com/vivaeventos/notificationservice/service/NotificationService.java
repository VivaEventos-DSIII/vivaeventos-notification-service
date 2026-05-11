package com.vivaeventos.notificationservice.service;

import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    public void sendConfirmacionCompra(PagoConfirmadoEvent event) {
        // TODO: construir Notification, persistir y enviar email (US-08)
    }

    public void sendTicketGenerado(TicketGeneradoEvent event) {
        // TODO: construir Notification, persistir y adjuntar boleta (US-08)
    }

    public void sendEventoCancelado(EventoCanceladoEvent event) {
        // TODO: construir Notification, persistir y enviar email de cancelación (US-12)
    }
}
