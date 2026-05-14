package com.vivaeventos.notificationservice;

import com.vivaeventos.notificationservice.dto.EventoCreadoEvent;
import com.vivaeventos.notificationservice.dto.PagoConfirmadoEvent;
import com.vivaeventos.notificationservice.dto.TicketGeneradoEvent;
import com.vivaeventos.notificationservice.module.Notification;
import com.vivaeventos.notificationservice.repository.NotificationRepository;
import com.vivaeventos.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private Scheduler quartzScheduler;
    @InjectMocks private NotificationService notificationService;

    private UUID userId, orderId, ticketId, eventId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID(); orderId = UUID.randomUUID();
        ticketId = UUID.randomUUID(); eventId = UUID.randomUUID();
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void dadoPagoConfirmado_cuandoSeEnviaConfirmacion_entoncesSeGuardaYEnviaEmail() {
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));
        notificationService.sendConfirmacionCompra(event);
        verify(notificationRepository, times(2)).save(any(Notification.class));
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        assertThat(cap.getValue().getTo()).contains("cliente@email.com");
        assertThat(cap.getValue().getSubject()).contains("Confirmación");
        assertThat(cap.getValue().getText()).contains("Carlos López");
    }

    @Test
    void dadoTicketGenerado_cuandoSeEnviaEmail_entoncesContieneDetallesDelEvento() {
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId, "cliente@email.com", "Ana García",
                "Concierto Rock", LocalDateTime.of(2026, 8, 15, 18, 0), "Parque Simón Bolívar");
        notificationService.sendTicketGenerado(event);
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        assertThat(cap.getValue().getText()).contains("Concierto Rock");
        assertThat(cap.getValue().getText()).contains("Parque Simón Bolívar");
        assertThat(cap.getValue().getText()).contains(ticketId.toString());
    }

    @Test
    void dadoEventoFuturo_cuandoSeRecibe_entoncesSeProgramaJobEnQuartz() throws SchedulerException {
        EventoCreadoEvent event = new EventoCreadoEvent(
                eventId, "Concierto Rock", LocalDateTime.now().plusDays(5), "Parque Simón Bolívar");
        notificationService.scheduleRecordatorio(event);
        verify(quartzScheduler).scheduleJob(any(), any());
    }

    @Test
    void dadoEventoEnMenos24Horas_cuandoSeRecibe_entoncesNOSeProgramaJob() throws SchedulerException {
        EventoCreadoEvent event = new EventoCreadoEvent(
                eventId, "Evento Urgente", LocalDateTime.now().plusHours(10), "Cualquier lugar");
        notificationService.scheduleRecordatorio(event);
        verify(quartzScheduler, never()).scheduleJob(any(), any());
    }

    @Test
    void dadoFalloDeEmail_cuandoSeEnviaConfirmacion_entoncesGuardaComoFailed() {
        doThrow(new org.springframework.mail.MailSendException("SMTP no disponible"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));
        notificationService.sendConfirmacionCompra(event);
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(cap.getAllValues().get(1).getErrorDetail()).isNotBlank();
    }
}