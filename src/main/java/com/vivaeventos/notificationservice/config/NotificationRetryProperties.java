package com.vivaeventos.notificationservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 *
 *
 * Mapea el bloque:
 * notification:
 *   retry:
 *     max-attempts: 5
 *     initial-delay-ms: 1000
 *     multiplier: 2.0
 */
@Component
@ConfigurationProperties(prefix = "notification.retry")
public class NotificationRetryProperties {

    private int maxAttempts = 5;
    private long initialDelayMs = 1000;
    private double multiplier = 2.0;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }

    public double getMultiplier() { return multiplier; }
    public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
}