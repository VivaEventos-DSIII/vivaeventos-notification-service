package com.vivaeventos.notificationservice;

import com.vivaeventos.notificationservice.job.ReminderJob;
import com.vivaeventos.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReminderJobTest {

    @Mock private NotificationService notificationService;
    @InjectMocks private ReminderJob reminderJob;

    @Test
    void dadoNotificationIdEnJobDataMap_cuandoSeEjecuta_entoncesDelegaASendRecordatorio()
            throws JobExecutionException {
        UUID notificationId = UUID.randomUUID();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("notificationId", notificationId.toString());

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);

        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);

        reminderJob.execute(context);

        verify(notificationService, times(1)).sendRecordatorio(notificationId);
    }

    @Test
    void dadoNotificationIdEnJobDataMap_cuandoSeEjecuta_entoncesNoInteractuaConNadaMas()
            throws JobExecutionException {
        UUID notificationId = UUID.randomUUID();

        JobDataMap dataMap = new JobDataMap();
        dataMap.put("notificationId", notificationId.toString());

        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(dataMap);

        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);

        reminderJob.execute(context);

        // Confirma que solo delega a sendRecordatorio — sin lógica adicional en el job
        verifyNoMoreInteractions(notificationService);
    }
}
