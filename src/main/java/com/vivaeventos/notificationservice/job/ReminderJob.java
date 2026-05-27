package com.vivaeventos.notificationservice.job;

import com.vivaeventos.notificationservice.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job de Quartz ejecutado 24h antes del evento.
 * Lee el ID del registro pre-creado (RETRY_SCHEDULED) y delega a sendRecordatorio,
 * que actualiza ese mismo registro en lugar de crear uno nuevo.
 */
@Slf4j
@Component
public class ReminderJob implements Job {

    @Autowired
    private NotificationService notificationService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        UUID notificationId = UUID.fromString(
                context.getJobDetail().getJobDataMap().getString("notificationId"));
        log.info("Ejecutando ReminderJob para notificación {}", notificationId);
        notificationService.sendRecordatorio(notificationId);
        log.info("ReminderJob completado para notificación {}", notificationId);
    }
}
