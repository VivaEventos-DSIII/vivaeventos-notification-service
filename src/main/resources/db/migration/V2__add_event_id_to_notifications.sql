ALTER TABLE notifications ADD COLUMN event_id UUID;
CREATE INDEX idx_notifications_event_id ON notifications(event_id)
    WHERE event_id IS NOT NULL;
