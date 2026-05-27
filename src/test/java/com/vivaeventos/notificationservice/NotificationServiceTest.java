package com.vivaeventos.notificationservice;

import com.vivaeventos.notificationservice.config.NotificationRetryProperties;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private Scheduler quartzScheduler;
    @Mock private NotificationRetryProperties retryProperties;
    @InjectMocks private NotificationService notificationService;

    private UUID userId, orderId, ticketId;

    @BeforeEach
    void setUp() {
        userId   = UUID.randomUUID();
        orderId  = UUID.randomUUID();
        ticketId = UUID.randomUUID();

        // save() devuelve una copia con ID generado, simulando comportamiento de BD
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    return Notification.builder()
                            .id(UUID.randomUUID())
                            .recipientId(n.getRecipientId())
                            .recipientEmail(n.getRecipientEmail())
                            .type(n.getType())
                            .subject(n.getSubject())
                            .body(n.getBody())
                            .status(n.getStatus())
                            .attempts(n.getAttempts())
                            .maxAttempts(n.getMaxAttempts())
                            .scheduledAt(n.getScheduledAt())
                            .createdAt(n.getCreatedAt())
                            .build();
                });

        when(retryProperties.getMaxAttempts()).thenReturn(5);
        when(retryProperties.getInitialDelayMs()).thenReturn(1000L);
        when(retryProperties.getMultiplier()).thenReturn(2.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: Confirmación de compra
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoPagoConfirmado_cuandoSeEnviaConfirmacion_entoncesRecipientIdNuncaEsNull() {
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getRecipientId()).isNotNull();
        assertThat(cap.getAllValues().get(0).getRecipientId()).isEqualTo(userId);
    }

    @Test
    void dadoPagoConfirmado_cuandoSeEnviaConfirmacion_entoncesEmailContieneOrdenYCliente() {
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        SimpleMailMessage email = emailCap.getValue();
        assertThat(email.getTo()).contains("cliente@email.com");
        assertThat(email.getSubject()).contains("Confirmación");
        assertThat(email.getText()).contains("Carlos López");
        assertThat(email.getText()).contains(orderId.toString());
    }

    @Test
    void dadoPagoConfirmado_cuandoSeGuarda_entoncesEstadoCambiaDePendingASent() {
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        List<Notification> guardadas = cap.getAllValues();
        assertThat(guardadas.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(guardadas.get(1).getStatus()).isEqualTo("SENT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: Ticket generado — segundo criterio (detalles del evento)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoTicketGenerado_cuandoSeEnviaEmail_entoncesContieneNombreEventoFechaYLugar() {
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "cliente@email.com", "Ana García",
                "Concierto Rock en el Parque",
                LocalDateTime.of(2026, 8, 15, 18, 0),
                "Parque Simón Bolívar");

        notificationService.sendTicketGenerado(event);

        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        String body = emailCap.getValue().getText();
        assertThat(body).contains("Concierto Rock en el Parque");
        assertThat(body).contains("Parque Simón Bolívar");
        assertThat(body).contains(ticketId.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: Programación del recordatorio
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoTicketFuturo_cuandoSeRecibe_entoncesSeProgramaJobEnQuartz()
            throws SchedulerException {
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Concierto Rock",
                LocalDateTime.now().plusDays(5),
                "Parque Simón Bolívar");

        notificationService.scheduleRecordatorioPorTicket(event);

        verify(quartzScheduler, times(1)).scheduleJob(any(), any());
    }

    @Test
    void dadoTicketFuturo_cuandoSeProgramaRecordatorio_entoncesScheduledAtNuncaEsNull()
            throws SchedulerException {
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Concierto Rock",
                LocalDateTime.now().plusDays(5),
                "Parque Simón Bolívar");

        notificationService.scheduleRecordatorioPorTicket(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getScheduledAt()).isNotNull();
        assertThat(cap.getValue().getStatus()).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void dadoTicketEnMenos24Horas_cuandoSeRecibe_entoncesNOSeProgramaJob()
            throws SchedulerException {
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Evento Urgente",
                LocalDateTime.now().plusHours(10),
                "Cualquier lugar");

        notificationService.scheduleRecordatorioPorTicket(event);

        verify(quartzScheduler, never()).scheduleJob(any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: Envío del recordatorio — actualiza registro existente sin duplicar
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoRecordatorio_cuandoSeEnvia_entoncesContieneInformacionDelEvento() {
        UUID notificationId = UUID.randomUUID();
        String eventName = "Concierto Rock en el Parque";
        String venue = "Parque Simón Bolívar";

        Notification existente = notificationPreCreada(notificationId, userId,
                "comprador@email.com",
                "⏰ Recordatorio: mañana es " + eventName,
                buildBodyConEvento(eventName, venue));
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(existente));

        notificationService.sendRecordatorio(notificationId);

        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        String body = emailCap.getValue().getText();
        assertThat(emailCap.getValue().getTo()).contains("comprador@email.com");
        assertThat(body).contains(eventName);
        assertThat(body).contains(venue);
    }

    @Test
    void dadoRecordatorio_cuandoSeEnvia_entoncesRecipientIdNuncaEsNull() {
        UUID notificationId = UUID.randomUUID();

        Notification existente = notificationPreCreada(notificationId, userId,
                "comprador@email.com", "⏰ Recordatorio", "Cuerpo del recordatorio");
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(existente));

        notificationService.sendRecordatorio(notificationId);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getRecipientId()).isNotNull();
        assertThat(cap.getAllValues().get(0).getRecipientId()).isEqualTo(userId);
    }

    @Test
    void dadoRecordatorio_cuandoSeEnviaExitosamente_entoncesActualizaRegistroExistente() {
        UUID notificationId = UUID.randomUUID();

        Notification existente = notificationPreCreada(notificationId, userId,
                "comprador@email.com", "⏰ Recordatorio", "Cuerpo");
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(existente));

        notificationService.sendRecordatorio(notificationId);

        // Un solo save (el update del registro existente) — sin duplicados
        verify(notificationRepository, times(1)).save(any());
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo("SENT");
        assertThat(cap.getValue().getAttempts()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RQ-04: Trazabilidad y reintentos con backoff exponencial
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoFalloDeEmailConUnSoloIntento_cuandoFalla_entoncesGuardaComoFailed() {
        // maxAttempts = 1: sin reintentos → FAILED inmediato
        when(retryProperties.getMaxAttempts()).thenReturn(1);
        doThrow(new MailSendException("SMTP no disponible"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(cap.getAllValues().get(1).getErrorDetail()).isNotBlank();
    }

    @Test
    void dadoFalloDeEmailConReintentosDisponibles_cuandoFalla_entoncesEstadoEsRetryScheduled()
            throws SchedulerException {
        // maxAttempts = 5: primer fallo → RETRY_SCHEDULED
        doThrow(new MailSendException("SMTP no disponible"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("RETRY_SCHEDULED");
        // Quartz debe haber recibido el job de reintento
        verify(quartzScheduler, times(1)).scheduleJob(any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // maxAttempts proviene de properties, no hardcodeado
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadaConfiguracion3Reintentos_cuandoSeCreaNot_entoncesMaxAttemptsEs3() {
        when(retryProperties.getMaxAttempts()).thenReturn(3);
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos", BigDecimal.valueOf(85000));

        notificationService.sendConfirmacionCompra(event);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getMaxAttempts()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Notification notificationPreCreada(UUID id, UUID recipientId,
                                               String email, String subject, String body) {
        return Notification.builder()
                .id(id)
                .recipientId(recipientId)
                .recipientEmail(email)
                .type("EVENT_REMINDER")
                .subject(subject)
                .body(body)
                .status("RETRY_SCHEDULED")
                .attempts(0)
                .maxAttempts(5)
                .scheduledAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String buildBodyConEvento(String eventName, String venue) {
        return "Detalles del evento:\n  • Evento: " + eventName + "\n  • Lugar:  " + venue;
    }
}
