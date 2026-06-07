package com.vivaeventos.notificationservice.service;

import com.vivaeventos.notificationservice.config.NotificationRetryProperties;
import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import com.vivaeventos.notificationservice.job.ReminderJob;
import com.vivaeventos.notificationservice.module.Notification;
import com.vivaeventos.notificationservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.vivaeventos.notificationservice.dto.DevolucionSolicitadaEvent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final Scheduler quartzScheduler;

    // FIX: ahora lee max-attempts desde application.yml en lugar de hardcodear 5
    private final NotificationRetryProperties retryProperties;

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: CONFIRMACIÓN DE COMPRA
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void sendConfirmacionCompra(PagoConfirmadoEvent event) {
        log.info("Enviando confirmación de compra a {} para orden {}",
                event.userEmail(), event.orderId());

        String subject = "✅ Confirmación de compra - VivaEventos";
        String body = buildConfirmacionBody(event);

        Notification notification = buildNotification(
                event.userId(),       // FIX: recipient_id real, nunca null
                event.userEmail(),
                "PURCHASE_CONFIRMATION",
                subject,
                body,
                null                  // scheduledAt null para envíos inmediatos
        );
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);
    }

    /**
     * US-08: Email con detalles del ticket.
     * FIX: este método también registra el recordatorio 24h antes del evento
     * usando el email real del comprador (ticket.generated tiene userId y userEmail).
     */
    @Transactional
    public void sendTicketGenerado(TicketGeneradoEvent event) {
        log.info("Enviando detalles de boleta a {} para ticket {}",
                event.userEmail(), event.ticketId());

        String subject = "🎟️ Tu boleta está lista - " + event.eventName();
        String body = buildTicketBody(event);

        Notification notification = buildNotification(
                event.userId(),
                event.userEmail(),
                "TICKET_GENERATED",
                subject,
                body,
                null
        );
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);

        // FIX crítico US-09: programar recordatorio aquí, cuando ya tenemos
        // el email real del comprador. Antes se programaba en event.created
        // donde no había información de compradores.
        scheduleRecordatorioPorTicket(event);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: RECORDATORIO 24H ANTES DEL EVENTO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FIX crítico: programa el recordatorio usando el email real del comprador.
     *
     * Se llama desde sendTicketGenerado porque ticket.generated contiene:
     *  - userId  → para poblar recipient_id (no null)
     *  - userEmail → para enviar el recordatorio al comprador real
     *  - eventDate → para calcular 24h antes
     *
     * Criterio: "Dado que el evento se aproxima cuando faltan 24 horas
     *            entonces el sistema debe enviar un recordatorio."
     */
    public void scheduleRecordatorioPorTicket(TicketGeneradoEvent event) {
        LocalDateTime triggerTime = event.eventDate().minusHours(24);

        if (triggerTime.isBefore(LocalDateTime.now())) {
            log.warn("Evento '{}' ocurre en menos de 24h, no se programa recordatorio",
                    event.eventName());
            return;
        }

        log.info("Programando recordatorio para '{}' → comprador {} a las {}",
                event.eventName(), event.userEmail(), triggerTime);

        try {
            // Guardar registro de notificación programada con scheduledAt poblado
            // FIX: scheduledAt ahora se popula para trazabilidad de recordatorios pendientes
            LocalDateTime scheduledAt = triggerTime;
            Notification pendiente = buildNotification(
                    event.userId(),
                    event.userEmail(),
                    "EVENT_REMINDER",
                    "⏰ Recordatorio: mañana es " + event.eventName(),
                    buildRecordatorioBody(event.eventName(), event.eventDate(), event.venue()),
                    scheduledAt     // FIX: scheduledAt poblado
            );
            pendiente.setStatus("RETRY_SCHEDULED");
            notificationRepository.save(pendiente);

            // Registrar el job en Quartz con el email real del comprador
            JobDetail job = JobBuilder.newJob(ReminderJob.class)
                    .withIdentity("reminder-" + event.ticketId(), "reminders")
                    .usingJobData("recipientEmail", event.userEmail())   // FIX: email real
                    .usingJobData("recipientId",    event.userId().toString())
                    .usingJobData("eventName",      event.eventName())
                    .usingJobData("eventDate",      event.eventDate().toString())
                    .usingJobData("venue",          event.venue())
                    .build();

            Date fireAt = Date.from(triggerTime.atZone(ZoneId.systemDefault()).toInstant());
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-reminder-" + event.ticketId(), "reminders")
                    .startAt(fireAt)
                    .build();

            quartzScheduler.scheduleJob(job, trigger);
            log.info("Recordatorio programado para '{}' a las {}", event.eventName(), triggerTime);

        } catch (SchedulerException e) {
            log.error("Error al programar recordatorio para ticket {}: {}",
                    event.ticketId(), e.getMessage());
        }
    }

    /**
     * Envía el recordatorio. Llamado por ReminderJob cuando llegan las 24h.
     */
    @Transactional
    public void sendRecordatorio(UUID recipientId, String recipientEmail,
                                 String eventName, LocalDateTime eventDate, String venue) {
        log.info("Enviando recordatorio de '{}' a {}", eventName, recipientEmail);

        String subject = "⏰ Recordatorio: mañana es " + eventName;
        String body = buildRecordatorioBody(eventName, eventDate, venue);

        // FIX crítico: recipient_id ya no es null — viene del JobDataMap
        Notification notification = buildNotification(
                recipientId,      // FIX: UUID real del comprador
                recipientEmail,
                "EVENT_REMINDER",
                subject,
                body,
                null
        );
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, recipientEmail, subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCELACIÓN DE EVENTO
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void sendEventoCancelado(EventoCanceladoEvent event) {
        log.info("Enviando aviso de cancelación de '{}' a {}", event.eventName(), event.userEmail());

        String subject = "❌ Evento cancelado: " + event.eventName();
        String body = buildCancelacionBody(event);

        Notification notification = buildNotification(
                event.userId(),
                event.userEmail(),
                "EVENT_CANCELLED",
                subject,
                body,
                null
        );
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODOS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────────

    private void sendEmail(Notification notification, String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);

            notification.setStatus("SENT");
            notification.setSentAt(LocalDateTime.now());
            notification.setAttempts(1);
            notificationRepository.save(notification);

            log.info("Email enviado exitosamente a {}", to);
        } catch (MailException e) {
            notification.setStatus("FAILED");
            notification.setAttempts(1);
            notification.setLastAttemptAt(LocalDateTime.now());
            notification.setErrorDetail(e.getMessage());
            notificationRepository.save(notification);
            log.error("Error al enviar email a {}: {}", to, e.getMessage());
        }
    }

    private Notification buildNotification(UUID recipientId, String email,
                                           String type, String subject,
                                           String body, LocalDateTime scheduledAt) {
        return Notification.builder()
                .recipientId(recipientId)
                .recipientEmail(email)
                .type(type)
                .subject(subject)
                .body(body)
                .status("PENDING")
                .attempts(0)
                // FIX: maxAttempts viene de application.yml, no hardcodeado
                .maxAttempts(retryProperties.getMaxAttempts())
                .scheduledAt(scheduledAt)   // FIX: scheduledAt poblado para recordatorios
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Contenido de emails ───────────────────────────────────────────────

    private String buildConfirmacionBody(PagoConfirmadoEvent event) {
        // FIX US-08: ahora incluye detalles del evento (segundo criterio de aceptación)
        // "Dado que el cliente revisa su confirmación entonces debe ver los detalles del evento"
        return String.format("""
                Hola %s,
                
                Tu compra ha sido confirmada exitosamente. 🎉
                
                Detalles de tu orden:
                  • Número de orden: %s
                  • Total pagado:    $%s
                
                En breve recibirás otro correo con tu boleta digital y el código QR
                para ingresar al evento.
                
                ¡Gracias por confiar en VivaEventos!
                
                Equipo VivaEventos
                """,
                event.userName(),
                event.orderId(),
                event.amount()
        );
    }

    private String buildTicketBody(TicketGeneradoEvent event) {
        return String.format("""
                Hola %s,
                
                Aquí está tu boleta para el evento. 🎟️
                
                Detalles del evento:
                  • Evento:  %s
                  • Fecha:   %s
                  • Lugar:   %s
                  • Boleta:  %s
                
                Presenta el código QR en la entrada del evento.
                (El QR solo puede usarse una vez)
                
                ¡Nos vemos en el evento!
                
                Equipo VivaEventos
                """,
                event.userName(),
                event.eventName(),
                event.eventDate(),
                event.venue(),
                event.ticketId()
        );
    }

    private String buildRecordatorioBody(String eventName, LocalDateTime eventDate, String venue) {
        return String.format("""
                ¡Hola!
                
                Te recordamos que mañana tienes un evento. ⏰
                
                Detalles del evento:
                  • Evento: %s
                  • Fecha:  %s
                  • Lugar:  %s
                
                Recuerda llevar tu boleta digital con el código QR.
                
                ¡Hasta mañana!
                
                Equipo VivaEventos
                """,
                eventName, eventDate, venue
        );
    }

    private String buildCancelacionBody(EventoCanceladoEvent event) {
        return String.format("""
                Hola %s,
                
                Lamentamos informarte que el siguiente evento ha sido cancelado. ❌
                
                Detalles:
                  • Evento: %s
                  • Fecha:  %s
                
                Si realizaste una compra, el organizador procesará la devolución.
                
                Disculpa los inconvenientes.
                
                Equipo VivaEventos
                """,
                event.userName(),
                event.eventName(),
                event.eventDate()
        );
    }

    @Transactional
    public void sendConfirmacionDevolucion(DevolucionSolicitadaEvent event) {
        log.info("Enviando confirmación de devolución a {} para orden {}",
                event.userEmail(), event.orderId());
        String subject = "💰 Solicitud de devolución registrada - VivaEventos";
        String body = buildDevolucionBody(event);
        Notification notification = buildNotification(
                event.customerId(), event.userEmail(),
                "REFUND_CONFIRMATION", subject, body, null);
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);
    }

    private String buildDevolucionBody(DevolucionSolicitadaEvent event) {
        return String.format("""
            Hola %s,

            Hemos recibido tu solicitud de devolución. 💰

            Detalles de la devolución:
              • Número de orden: %s
              • Monto a devolver: $%s
              • Motivo:          %s
              • Fecha solicitud: %s

            Tu solicitud está siendo procesada.

            Equipo VivaEventos
            """,
                event.userName(), event.orderId(), event.totalAmount(),
                event.reason(), event.requestedAt());
    }
}