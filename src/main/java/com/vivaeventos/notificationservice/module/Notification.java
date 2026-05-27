package com.vivaeventos.notificationservice.module;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private UUID recipientId;

    private String recipientEmail;

    private String type;

    private String subject;

    private String body;

    private String status;

    private Integer attempts;

    private Integer maxAttempts;

    private LocalDateTime lastAttemptAt;

    private LocalDateTime sentAt;

    private String errorDetail;

    private LocalDateTime scheduledAt;

    private LocalDateTime createdAt;
}
