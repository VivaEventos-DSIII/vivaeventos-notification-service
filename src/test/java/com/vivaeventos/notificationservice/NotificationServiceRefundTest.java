package com.vivaeventos.notificationservice;

import com.vivaeventos.notificationservice.config.NotificationRetryProperties;
import com.vivaeventos.notificationservice.dto.DevolucionSolicitadaEvent;
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

/**
 * Tests unitarios para la confirmación de devolución (US-13).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceRefundTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private JavaMailSender mailSender;
    @Mock private Scheduler quartzScheduler;
    @Mock private NotificationRetryProperties retryProperties;
    @InjectMocks private NotificationService notificationService;

    private UUID customerId;
    private UUID orderId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        orderId    = UUID.randomUUID();
        eventId    = UUID.randomUUID();

        when(retryProperties.getMaxAttempts()).thenReturn(5);
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Criterio: "Dado que la devolución se procesa entonces el cliente
    //            debe recibir confirmación."
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dadoDevolucionSolicitada_cuandoSeEnviaConfirmacion_entoncesEmailLlegaAlCliente() {
        // GIVEN
        DevolucionSolicitadaEvent event = new DevolucionSolicitadaEvent(
                orderId, eventId, customerId,
                "cliente@email.com",
                "Cliente Test",
                new BigDecimal("240000"),
                "EVENTO_CANCELADO",
                LocalDateTime.now()
        );

        // WHEN
        notificationService.sendConfirmacionDevolucion(event);

        // THEN — se envía exactamente 1 email al cliente
        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(emailCap.capture());
        assertThat(emailCap.getValue().getTo()).contains("cliente@email.com");
        assertThat(emailCap.getValue().getSubject()).contains("devolución");
    }

    @Test
    void dadoDevolucionSolicitada_cuandoSeEnviaConfirmacion_entoncesEmailContieneMontoYOrden() {
        // GIVEN
        DevolucionSolicitadaEvent event = new DevolucionSolicitadaEvent(
                orderId, eventId, customerId,
                "cliente@email.com",
                "Cliente Test",
                new BigDecimal("240000"),
                "EVENTO_CANCELADO",
                LocalDateTime.now()
        );

        // WHEN
        notificationService.sendConfirmacionDevolucion(event);

        // THEN — el cliente debe ver el monto y el número de orden en el email
        ArgumentCaptor<SimpleMailMessage> emailCap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(emailCap.capture());
        String body = emailCap.getValue().getText();
        assertThat(body).contains(orderId.toString());
        assertThat(body).contains("240000");
        assertThat(body).contains("EVENTO_CANCELADO");
    }

    @Test
    void dadoDevolucionSolicitada_cuandoSeGuarda_entoncesRecipientIdNuncaEsNull() {
        // GIVEN
        DevolucionSolicitadaEvent event = new DevolucionSolicitadaEvent(
                orderId, eventId, customerId,
                "cliente@email.com",
                "Cliente Test",
                new BigDecimal("240000"),
                "EVENTO_CANCELADO",
                LocalDateTime.now()
        );

        // WHEN
        notificationService.sendConfirmacionDevolucion(event);

        // THEN — recipient_id nunca null (constraint NOT NULL en BD)
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getRecipientId()).isNotNull();
        assertThat(cap.getAllValues().get(0).getRecipientId()).isEqualTo(customerId);
    }

    @Test
    void dadoDevolucionSolicitada_cuandoSeGuarda_entoncesEstadoCambiaDePendingASent() {
        // GIVEN
        DevolucionSolicitadaEvent event = new DevolucionSolicitadaEvent(
                orderId, eventId, customerId,
                "cliente@email.com",
                "Cliente Test",
                new BigDecimal("240000"),
                "EVENTO_CANCELADO",
                LocalDateTime.now()
        );

        // WHEN
        notificationService.sendConfirmacionDevolucion(event);

        // THEN
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        List<Notification> guardadas = cap.getAllValues();
        assertThat(guardadas.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(guardadas.get(1).getStatus()).isEqualTo("SENT");
    }

    @Test
    void dadoFalloDeEmail_cuandoSeEnviaConfirmacionDevolucion_entoncesGuardaComoFailed() {
        // GIVEN
        doThrow(new MailSendException("SMTP no disponible"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        DevolucionSolicitadaEvent event = new DevolucionSolicitadaEvent(
                orderId, eventId, customerId,
                "cliente@email.com",
                "Cliente Test",
                new BigDecimal("240000"),
                "EVENTO_CANCELADO",
                LocalDateTime.now()
        );

        // WHEN — no debe lanzar excepción
        notificationService.sendConfirmacionDevolucion(event);

        // THEN
        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(cap.getAllValues().get(1).getErrorDetail()).isNotBlank();
    }
}