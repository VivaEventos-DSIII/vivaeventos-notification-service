package com.vivaeventos.notificationservice.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuración adicional de Kafka. Spring Boot auto-configura el ConsumerFactory
 * desde application.yml. Añadir aquí ConsumerFactory beans con type-mapping
 * si los productores no incluyen cabeceras de tipo en los mensajes.
 */
@Configuration
public class KafkaConsumerConfig {
}
