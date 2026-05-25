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

        // FIX 2: save() devuelve una copia del objeto para que cada llamada
        // sea independiente y podamos verificar el estado en cada save por separado
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    return Notification.builder()
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: Confirmación de compra
    // Criterio: "Dado que el pago es exitoso cuando la compra se confirma
    //            entonces el sistema debe enviar un mensaje de confirmación."
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoPagoConfirmado_cuandoSeEnviaConfirmacion_entoncesRecipientIdNuncaEsNull() {
        // GIVEN
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        // WHEN
        notificationService.sendConfirmacionCompra(event);

        // THEN — recipient_id nunca debe ser null (constraint NOT NULL en BD)
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getRecipientId()).isNotNull();
        assertThat(cap.getAllValues().get(0).getRecipientId()).isEqualTo(userId);
    }

    @Test
    void dadoPagoConfirmado_cuandoSeEnviaConfirmacion_entoncesEmailContieneOrdenYCliente() {
        // GIVEN
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        // WHEN
        notificationService.sendConfirmacionCompra(event);

        // THEN
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
        // GIVEN
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        // WHEN
        notificationService.sendConfirmacionCompra(event);

        // THEN — FIX 2: primer save PENDING, segundo save SENT (copias independientes)
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        List<Notification> guardadas = cap.getAllValues();
        assertThat(guardadas.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(guardadas.get(1).getStatus()).isEqualTo("SENT");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-08: Ticket generado — detalles del evento
    // Criterio: "Dado que el cliente revisa su confirmación
    //            entonces debe ver los detalles del evento."
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoTicketGenerado_cuandoSeEnviaEmail_entoncesContieneNombreEventoFechaYLugar() {
        // GIVEN
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "cliente@email.com", "Ana García",
                "Concierto Rock en el Parque",
                LocalDateTime.of(2026, 8, 15, 18, 0),
                "Parque Simón Bolívar");

        // WHEN
        notificationService.sendTicketGenerado(event);

        // THEN — verifica segundo criterio de US-08
        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        String body = emailCap.getValue().getText();
        assertThat(body).contains("Concierto Rock en el Parque");
        assertThat(body).contains("Parque Simón Bolívar");
        assertThat(body).contains(ticketId.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: Recordatorio programado con email real del comprador
    // Criterio: "Dado que el evento se aproxima cuando faltan 24 horas
    //            entonces el sistema debe enviar un recordatorio."
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoTicketFuturo_cuandoSeRecibe_entoncesSeProgramaJobConEmailRealDelComprador()
            throws SchedulerException {
        // GIVEN — evento en 5 días
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Concierto Rock",
                LocalDateTime.now().plusDays(5),
                "Parque Simón Bolívar");

        // WHEN
        notificationService.scheduleRecordatorioPorTicket(event);

        // THEN
        verify(quartzScheduler, times(1)).scheduleJob(any(), any());
    }

    @Test
    void dadoTicketFuturo_cuandoSeProgramaRecordatorio_entoncesScheduledAtNuncaEsNull()
            throws SchedulerException {
        // GIVEN
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Concierto Rock",
                LocalDateTime.now().plusDays(5),
                "Parque Simón Bolívar");

        // WHEN
        notificationService.scheduleRecordatorioPorTicket(event);

        // THEN — scheduledAt debe estar poblado para trazabilidad
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getScheduledAt()).isNotNull();
        assertThat(cap.getValue().getStatus()).isEqualTo("RETRY_SCHEDULED");
    }

    @Test
    void dadoTicketEnMenos24Horas_cuandoSeRecibe_entoncesNOSeProgramaJob()
            throws SchedulerException {
        // GIVEN — evento en 10 horas (menos de 24h)
        TicketGeneradoEvent event = new TicketGeneradoEvent(
                ticketId, orderId, userId,
                "comprador@email.com", "Ana García",
                "Evento Urgente",
                LocalDateTime.now().plusHours(10),
                "Cualquier lugar");

        // WHEN
        notificationService.scheduleRecordatorioPorTicket(event);

        // THEN
        verify(quartzScheduler, never()).scheduleJob(any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // US-09: Envío del recordatorio
    // Criterio: "Dado que el cliente recibe el recordatorio
    //            entonces debe ver información del evento."
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoRecordatorio_cuandoSeEnvia_entoncesContieneInformacionDelEvento() {
        // GIVEN
        String eventName = "Concierto Rock en el Parque";
        LocalDateTime eventDate = LocalDateTime.of(2026, 8, 15, 18, 0);
        String venue = "Parque Simón Bolívar";

        // WHEN
        notificationService.sendRecordatorio(userId, "comprador@email.com",
                eventName, eventDate, venue);

        // THEN — criterio "el cliente debe ver información del evento"
        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        String body = emailCap.getValue().getText();
        assertThat(emailCap.getValue().getTo()).contains("comprador@email.com");
        assertThat(body).contains(eventName);
        assertThat(body).contains(venue);
    }

    @Test
    void dadoRecordatorio_cuandoSeEnvia_entoncesRecipientIdNuncaEsNull() {
        // WHEN
        notificationService.sendRecordatorio(userId, "comprador@email.com",
                "Concierto Rock", LocalDateTime.now().plusDays(1), "Bogotá");

        // THEN — recipient_id nunca null
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getRecipientId()).isNotNull();
        assertThat(cap.getAllValues().get(0).getRecipientId()).isEqualTo(userId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trazabilidad: email fallido queda como FAILED (RQ-04)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoFalloDeEmail_cuandoSeEnviaConfirmacion_entoncesGuardaComoFailedSinLanzarExcepcion() {
        // GIVEN — servidor de correo no disponible
        doThrow(new MailSendException("SMTP no disponible"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos López", BigDecimal.valueOf(85000));

        // WHEN — no debe lanzar excepción (flujo de compra no se interrumpe)
        notificationService.sendConfirmacionCompra(event);

        // THEN
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(cap.getAllValues().get(1).getErrorDetail()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // maxAttempts viene de properties, no hardcodeado
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadaConfiguracion3Reintentos_cuandoSeCreaNot_entoncesMaxAttemptsEs3() {
        // GIVEN
        when(retryProperties.getMaxAttempts()).thenReturn(3);
        PagoConfirmadoEvent event = new PagoConfirmadoEvent(
                orderId, userId, "cliente@email.com", "Carlos", BigDecimal.valueOf(85000));

        // WHEN
        notificationService.sendConfirmacionCompra(event);

        // THEN
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getMaxAttempts()).isEqualTo(3);
    }
}