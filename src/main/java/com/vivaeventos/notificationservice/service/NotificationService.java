package com.vivaeventos.notificationservice.service;

import com.vivaeventos.notificationservice.dto.EventoCanceladoEvent;
import com.vivaeventos.notificationservice.dto.EventoCreadoEvent;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Servicio central de notificaciones.
 *
 * RESPONSABILIDADES:
 *  - US-08: Enviar confirmación de compra cuando el pago es exitoso
 *  - US-08: Enviar email con detalles del ticket cuando se genera la boleta
 *  - US-09: Programar recordatorio 24h antes del evento usando Quartz
 *  - Persistir cada notificación en BD con su estado (SENT / FAILED)
 *  - Si el email falla, marcar como FAILED para trazabilidad (RQ-04)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final JavaMailSender mailSender;   // Spring Boot lo autoconfigura con application.yml
    private final Scheduler quartzScheduler;   // Quartz lo autoconfigura con spring.quartz

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: CONFIRMACIÓN DE COMPRA
    // Se dispara cuando llega el evento Kafka "order.confirmed"
    // Criterio: "Dado que el pago es exitoso cuando la compra se confirma
    //            entonces el sistema debe enviar un mensaje de confirmación."
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envía confirmación de compra al cliente.
     *
     * Flujo:
     *  1. Construir el contenido del email con los datos del pago
     *  2. Persistir la notificación en BD con estado PENDING
     *  3. Intentar enviar el email
     *  4. Actualizar estado a SENT o FAILED según resultado
     *
     * @param event datos del pago confirmado que llegaron por Kafka
     */
    @Transactional
    public void sendConfirmacionCompra(PagoConfirmadoEvent event) {
        log.info("Enviando confirmación de compra a {} para orden {}",
                event.userEmail(), event.orderId());

        // 1. Construir el subject y body del email
        String subject = "✅ Confirmación de compra - VivaEventos";
        String body = buildConfirmacionBody(event);

        // 2. Persistir en BD con estado PENDING (antes de intentar enviar)
        //    Esto garantiza trazabilidad incluso si el envío falla
        Notification notification = buildNotification(
                event.userId(),
                event.userEmail(),
                "PURCHASE_CONFIRMATION",
                subject,
                body
        );
        Notification saved = notificationRepository.save(notification);

        // 3. Intentar enviar el email
        sendEmail(saved, event.userEmail(), subject, body);
    }

    /**
     * Envía email con los detalles del ticket generado.
     *
     * Criterio: "Dado que el cliente revisa su confirmación
     *            entonces debe ver los detalles del evento."
     *
     * @param event datos del ticket generado que llegaron por Kafka
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
                body
        );
        Notification saved = notificationRepository.save(notification);

        sendEmail(saved, event.userEmail(), subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: RECORDATORIO DE EVENTO
    // Se dispara cuando llega el evento Kafka "event.created"
    // Criterio: "Dado que el evento se aproxima cuando faltan 24 horas
    //            entonces el sistema debe enviar un recordatorio."
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Programa un job de Quartz para enviar el recordatorio 24 horas antes del evento.
     *
     * ¿Por qué Quartz y no un simple Thread.sleep()?
     * - Quartz persiste el job en BD: si el servidor se reinicia, el job no se pierde
     * - Permite programar miles de recordatorios sin bloquear hilos
     * - Es exactamente para lo que fue diseñado: tareas programadas en el futuro
     *
     * @param event datos del evento creado que llegaron por Kafka
     */
    public void scheduleRecordatorio(EventoCreadoEvent event) {
        // Calcular la fecha de disparo: 24 horas antes del evento
        LocalDateTime triggerTime = event.eventDate().minusHours(24);

        // Si el evento es en menos de 24 horas desde ahora, no tiene sentido programarlo
        if (triggerTime.isBefore(LocalDateTime.now())) {
            log.warn("Evento {} ocurre en menos de 24h, no se programa recordatorio", event.eventId());
            return;
        }

        log.info("Programando recordatorio para evento '{}' a las {}",
                event.eventName(), triggerTime);

        try {
            // JobDetail → describe QUÉ ejecutar (nuestra clase ReminderJob)
            // JobDataMap → datos que necesita el job cuando se ejecute
            JobDetail job = JobBuilder.newJob(ReminderJob.class)
                    .withIdentity("reminder-" + event.eventId(), "reminders")
                    .usingJobData("eventId",   event.eventId().toString())
                    .usingJobData("eventName", event.eventName())
                    .usingJobData("eventDate", event.eventDate().toString())
                    .usingJobData("venue",     event.venue())
                    .build();

            // Trigger → dice CUÁNDO ejecutar el job (24h antes del evento)
            Date fireAt = Date.from(triggerTime.atZone(ZoneId.systemDefault()).toInstant());

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-reminder-" + event.eventId(), "reminders")
                    .startAt(fireAt)
                    .build();

            // Registrar el job en Quartz (lo persiste en BD automáticamente)
            quartzScheduler.scheduleJob(job, trigger);

            log.info("Recordatorio programado para evento {} a las {}", event.eventId(), triggerTime);

        } catch (SchedulerException e) {
            log.error("Error al programar recordatorio para evento {}: {}",
                    event.eventId(), e.getMessage());
        }
    }

    /**
     * Envía el recordatorio a un cliente específico.
     * Es llamado por ReminderJob cuando llega la hora programada.
     *
     * Criterio: "Dado que el cliente recibe el recordatorio
     *            entonces debe ver información del evento."
     *
     * @param recipientEmail email del destinatario
     * @param eventName      nombre del evento
     * @param eventDate      fecha del evento
     * @param venue          lugar del evento
     */
    @Transactional
    public void sendRecordatorio(String recipientEmail, String eventName,
                                 LocalDateTime eventDate, String venue) {
        log.info("Enviando recordatorio de evento '{}' a {}", eventName, recipientEmail);

        String subject = "⏰ Recordatorio: mañana es " + eventName;
        String body = buildRecordatorioBody(eventName, eventDate, venue);

        // Para el recordatorio no tenemos recipientId, usamos null
        Notification notification = buildNotification(
                null,
                recipientEmail,
                "EVENT_REMINDER",
                subject,
                body
        );
        Notification saved = notificationRepository.save(notification);

        sendEmail(saved, recipientEmail, subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancelación de evento (ya existía, completamos la implementación)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public void sendEventoCancelado(EventoCanceladoEvent event) {
        log.info("Enviando aviso de cancelación del evento '{}' a {}",
                event.eventName(), event.userEmail());

        String subject = "❌ Evento cancelado: " + event.eventName();
        String body = buildCancelacionBody(event);

        Notification notification = buildNotification(
                event.userId(),
                event.userEmail(),
                "EVENT_CANCELLED",
                subject,
                body
        );
        Notification saved = notificationRepository.save(notification);

        sendEmail(saved, event.userEmail(), subject, body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODOS PRIVADOS DE SOPORTE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envía el email y actualiza el estado de la notificación en BD.
     *
     * Si el envío falla:
     *  - Marca la notificación como FAILED (trazabilidad - RQ-04)
     *  - Guarda el mensaje de error en errorDetail
     *  - NO lanza excepción: el flujo de compra no se interrumpe por esto
     */
    private void sendEmail(Notification notification, String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);

            // Actualizar estado a SENT
            notification.setStatus("SENT");
            notification.setSentAt(LocalDateTime.now());
            notification.setAttempts(1);
            notificationRepository.save(notification);

            log.info("Email enviado exitosamente a {}", to);

        } catch (MailException e) {
            // Actualizar estado a FAILED con el detalle del error
            notification.setStatus("FAILED");
            notification.setAttempts(1);
            notification.setLastAttemptAt(LocalDateTime.now());
            notification.setErrorDetail(e.getMessage());
            notificationRepository.save(notification);

            log.error("Error al enviar email a {}: {}", to, e.getMessage());
            // No relanzamos la excepción — el flujo de compra sigue funcionando
        }
    }

    /**
     * Construye una notificación con estado PENDING para persistir antes de enviar.
     */
    private Notification buildNotification(java.util.UUID recipientId, String email,
                                           String type, String subject, String body) {
        return Notification.builder()
                .recipientId(recipientId)
                .recipientEmail(email)
                .type(type)
                .subject(subject)
                .body(body)
                .status("PENDING")
                .attempts(0)
                .maxAttempts(5)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── Constructores de contenido de emails ──────────────────────────────

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
                (El QR está asociado a este ticket y solo puede usarse una vez)
                
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
                
                Recuerda llevar tu boleta digital (el QR que te enviamos).
                
                ¡Hasta mañana!
                
                Equipo VivaEventos
                """,
                eventName,
                eventDate,
                venue
        );
    }

    private String buildCancelacionBody(EventoCanceladoEvent event) {
        return String.format("""
                Hola %s,
                
                Lamentamos informarte que el siguiente evento ha sido cancelado. ❌
                
                Detalles:
                  • Evento: %s
                  • Fecha:  %s
                
                Si realizaste una compra, el organizador procesará la devolución
                según los términos establecidos.
                
                Disculpa los inconvenientes.
                
                Equipo VivaEventos
                """,
                event.userName(),
                event.eventName(),
                event.eventDate()
        );
    }
}