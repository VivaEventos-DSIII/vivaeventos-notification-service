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
 * Job de Quartz que reintenta el envío de una notificación fallida.
 * Usa backoff exponencial definido en NotificationRetryProperties.
 */
@Slf4j
@Component
public class RetryEmailJob implements Job {

    @Autowired
    private NotificationService notificationService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        UUID notificationId = UUID.fromString(
                context.getJobDetail().getJobDataMap().getString("notificationId"));
        log.info("Ejecutando RetryEmailJob para notificación {}", notificationId);
        notificationService.sendRecordatorio(notificationId);
    }
}
