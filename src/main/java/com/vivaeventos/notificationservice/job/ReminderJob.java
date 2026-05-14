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

/**
 * Job de Quartz que se ejecuta 24 horas antes de cada evento.
 *
 * ¿Cómo funciona Quartz?
 *  1. NotificationService.scheduleRecordatorio() registra este job con una fecha de disparo
 *  2. Quartz persiste el job en la BD (tabla QRTZ_*)
 *  3. Cuando llega la fecha, Quartz instancia este Job y llama a execute()
 *  4. execute() lee los datos del evento del JobDataMap y envía el recordatorio
 *
 * Ventaja clave: si el servidor se reinicia, Quartz recupera los jobs pendientes
 * de la BD y los ejecuta cuando corresponda. No se pierden recordatorios.
 *
 * @Component → necesario para que Spring inyecte NotificationService aquí
 */
@Slf4j
@Component
public class ReminderJob implements Job {

    /**
     * @Autowired en campo es necesario aquí porque Quartz instancia el Job
     * con su propio mecanismo, no con el constructor de Spring.
     * Spring luego inyecta las dependencias vía AutowireCapableBeanFactory.
     */
    @Autowired
    private NotificationService notificationService;

    /**
     * Quartz llama a este método cuando llega la hora programada.
     *
     * JobExecutionContext → contiene el JobDataMap con los datos
     * que se guardaron al programar el job (eventName, eventDate, venue).
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // Leer los datos del evento que se guardaron al programar el job
        JobDataMap data = context.getJobDetail().getJobDataMap();

        String eventName = data.getString("eventName");
        String eventDateStr = data.getString("eventDate");
        String venue = data.getString("venue");
        String eventId = data.getString("eventId");

        log.info("Ejecutando ReminderJob para evento '{}' (id={})", eventName, eventId);

        // Convertir la fecha de String a LocalDateTime
        LocalDateTime eventDate = LocalDateTime.parse(eventDateStr);

        // Por ahora enviamos a una dirección genérica.
        // En una versión más completa, el job recibiría la lista de compradores
        // del order-service vía Feign y enviaría un recordatorio a cada uno.
        // Para el MVP esto demuestra el flujo completo.
        notificationService.sendRecordatorio(
                "recordatorio@vivaeventos.com",  // En producción: lista de compradores
                eventName,
                eventDate,
                venue
        );

        log.info("ReminderJob completado para evento '{}'", eventName);
    }
}