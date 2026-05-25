package com.vivaeventos.notificationservice.job;

import com.vivaeventos.notificationservice.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Job de Quartz ejecutado 24h antes del evento.
 *
 * FIX crítico US-09:
 *  - Lee recipientEmail del JobDataMap (email real del comprador)
 *  - Lee recipientId del JobDataMap (UUID real del comprador)
 *  - Llama a sendRecordatorio con esos datos reales
 *
 * Antes usaba "recordatorio@vivaeventos.com" hardcodeado — el cliente nunca recibía nada.
 */
@Slf4j
@Component
public class ReminderJob implements Job {

    @Autowired
    private NotificationService notificationService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        JobDataMap data = context.getJobDetail().getJobDataMap();

        // FIX: leer el email y ID reales del comprador desde el JobDataMap
        String recipientEmail = data.getString("recipientEmail");
        String recipientIdStr = data.getString("recipientId");
        String eventName      = data.getString("eventName");
        String eventDateStr   = data.getString("eventDate");
        String venue          = data.getString("venue");

        log.info("Ejecutando ReminderJob para evento '{}' → destinatario {}", eventName, recipientEmail);

        LocalDateTime eventDate = LocalDateTime.parse(eventDateStr);
        UUID recipientId = UUID.fromString(recipientIdStr);

        // FIX: ahora llama con el email real del comprador
        notificationService.sendRecordatorio(recipientId, recipientEmail, eventName, eventDate, venue);

        log.info("ReminderJob completado para evento '{}' → {}", eventName, recipientEmail);
    }
}