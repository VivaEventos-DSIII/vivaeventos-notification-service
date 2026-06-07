package com.vivaeventos.notificationservice.service;

import com.vivaeventos.notificationservice.config.NotificationRetryProperties;
import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import com.vivaeventos.notificationservice.job.ReminderJob;
import com.vivaeventos.notificationservice.job.RetryEmailJob;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;
    private final Scheduler quartzScheduler;
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
                event.userId(), event.userEmail(),
                "PURCHASE_CONFIRMATION", subject, body, null
        );
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);
    }

    @Transactional
    public void sendTicketGenerado(TicketGeneradoEvent event) {
        log.info("Enviando detalles de boleta a {} para ticket {}",
                event.userEmail(), event.ticketId());

        String subject = "🎟️ Tu boleta está lista - " + event.eventName();
        String body = buildTicketBody(event);

        Notification notification = buildNotification(
                event.userId(), event.userEmail(),
                "TICKET_GENERATED", subject, body, null
        );
        notification.setEventId(event.eventId());
        Notification saved = notificationRepository.save(notification);
        sendEmail(saved, event.userEmail(), subject, body);

        scheduleRecordatorioPorTicket(event);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: RECORDATORIO 24H ANTES DEL EVENTO
    // ─────────────────────────────────────────────────────────────────────────

    public void scheduleRecordatorioPorTicket(TicketGeneradoEvent event) {
        LocalDateTime triggerTime = event.eventDate().minusHours(24);
        if (triggerTime.isBefore(LocalDateTime.now())) {
            log.warn("Evento '{}' ocurre en menos de 24h, no se programa recordatorio",
                    event.eventName());
            return;
        }
        log.info("Programando recordatorio para '{}' → comprador {} a las {}",
                event.eventName(), event.userEmail(), triggerTime);

        // Guardar primero para obtener el ID generado por la BD
        Notification pendiente = buildNotification(
                event.userId(), event.userEmail(), "EVENT_REMINDER",
                "⏰ Recordatorio: mañana es " + event.eventName(),
                buildRecordatorioBody(event.eventName(), event.eventDate(), event.venue()),
                triggerTime
        );
        pendiente.setStatus("RETRY_SCHEDULED");
        Notification saved = notificationRepository.save(pendiente);

        Date fireAt = Date.from(triggerTime.atZone(ZoneId.systemDefault()).toInstant());
        JobDetail job = JobBuilder.newJob(ReminderJob.class)
                .withIdentity("reminder-" + event.ticketId(), "reminders")
                // Almacenar el ID del registro existente — ReminderJob lo actualiza,
                // no crea un segundo registro
                .usingJobData("notificationId", saved.getId().toString())
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("trigger-reminder-" + event.ticketId(), "reminders")
                .startAt(fireAt)
                .build();

        // Diferir hasta después del commit para evitar jobs huérfanos si la transacción falla
        scheduleAfterCommit(() -> {
            try {
                quartzScheduler.scheduleJob(job, trigger);
                log.info("Recordatorio programado para '{}' a las {}", event.eventName(), triggerTime);
            } catch (SchedulerException e) {
                log.error("Error al programar recordatorio para ticket {}: {}",
                        event.ticketId(), e.getMessage());
            }
        });
    }

    /**
     * Llamado por ReminderJob (primera ejecución) y RetryEmailJob (reintentos).
     * Carga el registro existente y realiza el envío — sin crear duplicados.
     */
    @Transactional
    public void sendRecordatorio(UUID notificationId) {
        log.info("Ejecutando envío para notificación programada {}", notificationId);
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Notificación programada no encontrada: " + notificationId));
        sendEmail(notification, notification.getRecipientEmail(),
                notification.getSubject(), notification.getBody());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CANCELACIÓN DE EVENTO
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void sendEventoCancelado(EventoCanceladoEvent event) {
        log.info("Procesando cancelación de evento '{}'", event.eventName());

        List<Notification> tickets = notificationRepository
                .findByEventIdAndType(event.eventId(), "TICKET_GENERATED");

        if (tickets.isEmpty()) {
            log.info("Evento '{}' cancelado sin compradores registrados — sin notificaciones a enviar",
                    event.eventName());
            return;
        }

        Map<UUID, Notification> byBuyer = tickets.stream()
                .collect(Collectors.toMap(
                        Notification::getRecipientId,
                        n -> n,
                        (a, b) -> a
                ));

        String subject = "Evento cancelado: " + event.eventName();
        for (Notification buyer : byBuyer.values()) {
            log.info("Enviando aviso de cancelación de '{}' a {}", event.eventName(), buyer.getRecipientEmail());
            String body = buildCancelacionBody(buyer, event);
            Notification notif = buildNotification(
                    buyer.getRecipientId(), buyer.getRecipientEmail(),
                    "EVENT_CANCELLED", subject, body, null
            );
            notif.setEventId(event.eventId());
            Notification saved = notificationRepository.save(notif);
            sendEmail(saved, buyer.getRecipientEmail(), subject, body);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODOS PRIVADOS
    // ─────────────────────────────────────────────────────────────────────────

    private void sendEmail(Notification notification, String to, String subject, String body) {
        notification.setAttempts(notification.getAttempts() + 1);
        notification.setLastAttemptAt(LocalDateTime.now());
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);

            notification.setStatus("SENT");
            notification.setSentAt(LocalDateTime.now());
            notificationRepository.save(notification);
            log.info("Email enviado exitosamente a {} (intento {})", to, notification.getAttempts());
        } catch (MailException e) {
            notification.setErrorDetail(e.getMessage());
            if (notification.getAttempts() < notification.getMaxAttempts()) {
                notification.setStatus("RETRY_SCHEDULED");
                notificationRepository.save(notification);
                scheduleRetryJob(notification);
                log.warn("Fallo al enviar email a {} (intento {}). Reintento programado.",
                        to, notification.getAttempts());
            } else {
                notification.setStatus("FAILED");
                notificationRepository.save(notification);
                log.error("Fallo definitivo enviando a {} tras {} intentos: {}",
                        to, notification.getAttempts(), e.getMessage());
            }
        }
    }

    private void scheduleRetryJob(Notification notification) {
        long delay = (long)(retryProperties.getInitialDelayMs()
                * Math.pow(retryProperties.getMultiplier(), notification.getAttempts() - 1));
        Date fireAt = new Date(System.currentTimeMillis() + delay);

        JobDetail retryJob = JobBuilder.newJob(RetryEmailJob.class)
                .withIdentity("retry-" + notification.getId() + "-" + notification.getAttempts(), "retries")
                .usingJobData("notificationId", notification.getId().toString())
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .startAt(fireAt)
                .build();

        scheduleAfterCommit(() -> {
            try {
                quartzScheduler.scheduleJob(retryJob, trigger);
                log.info("Reintento programado para notificación {} (intento {}) en {}ms",
                        notification.getId(), notification.getAttempts(), delay);
            } catch (SchedulerException e) {
                log.error("No se pudo programar reintento para {}: {}",
                        notification.getId(), e.getMessage());
            }
        });
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
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
                .maxAttempts(retryProperties.getMaxAttempts())
                .scheduledAt(scheduledAt)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Contenido de emails ───────────────────────────────────────────────

    private String buildConfirmacionBody(PagoConfirmadoEvent event) {
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
                event.userName(), event.orderId(), event.amount()
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
                event.userName(), event.eventName(), event.eventDate(), event.venue(), event.ticketId()
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

    private String buildCancelacionBody(Notification buyer, EventoCanceladoEvent event) {
        return String.format("""
                Hola,

                Lamentamos informarte que el siguiente evento ha sido cancelado. ❌

                Detalles:
                  • Evento:  %s
                  • Fecha:   %s
                  • Lugar:   %s
                  • Motivo:  %s

                Si realizaste una compra, el organizador procesará la devolución.

                Disculpa los inconvenientes.

                Equipo VivaEventos
                """,
                event.eventName(), event.eventDate(), event.venue(), event.reason()
        );
    }
}
