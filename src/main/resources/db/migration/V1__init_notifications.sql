-- V1__init_notifications.sql
-- Schema inicial del notification-service
-- Historias: US-08, US-09, US-12, RQ-04 (0 pérdida, reintentos automáticos)

CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id    UUID           NOT NULL,
    recipient_email VARCHAR(255)   NOT NULL,
    type            VARCHAR(100)   NOT NULL,
    -- PURCHASE_CONFIRMATION | EVENT_REMINDER | EVENT_CANCELLED | REFUND_PROCESSED
    subject         VARCHAR(255)   NOT NULL,
    body            TEXT           NOT NULL,
    status          VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    -- PENDING | SENT | FAILED | RETRY_SCHEDULED
    attempts        INTEGER        NOT NULL DEFAULT 0,
    max_attempts    INTEGER        NOT NULL DEFAULT 5,   -- RQ-04
    last_attempt_at TIMESTAMP,
    sent_at         TIMESTAMP,
    error_detail    TEXT,
    scheduled_at    TIMESTAMP,     -- Para recordatorios programados (US-09)
    created_at      TIMESTAMP      NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_status       ON notifications(status);
CREATE INDEX idx_notifications_recipient    ON notifications(recipient_id);
CREATE INDEX idx_notifications_scheduled_at ON notifications(scheduled_at) WHERE scheduled_at IS NOT NULL;
